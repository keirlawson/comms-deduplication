package com.ovoenergy.comms.deduplication

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.duration._
import scala.compat.java8.DurationConverters._
import scala.collection.JavaConverters._

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.implicits._

import com.amazonaws.services.dynamodbv2.{AmazonDynamoDBAsync, AmazonDynamoDBAsyncClientBuilder}
import com.amazonaws.services.dynamodbv2.model._
import com.amazonaws.handlers._
import org.scanamo._
import org.scanamo.error.{DynamoReadError, NoPropertyOfType}

import model._
import ScanamoHelpers._

trait Deduplication[F[_], ID] {

  /**
    * Try to start a process.
    *
    * If the process with the giving id has never started before, this will start a new process and return Sample.notSeen
    * If another process with the same id has already completed, this will do nothing and return Sample.seen
    * If another process with the same id has already started and timeouted, this will do nothing and return Sample.seen
    * If another process with the same id is still running, this will wait until it will complete or timeout
    *
    * @param id The process id to start
    * @return Sample.unSeen or Sample.seen
    */
  def tryStartProcess(id: ID): F[Sample]

  /**
    * Complete a started process.
    *
    * After calling this function, any other call to [[tryStartProcess]] with the same id, will result in a [[Sample.seen]].
    *
    * @param id The process id to complete
    * @return Unit
    */
  def completeProcess(id: ID): F[Unit]

  /**
    * Do the best effort to ensure a process to be successfully executed only once.
    *
    * If the process has already runned successfully before, it will return [[None]].
    * Otherwise, it will return the process result wrapped in Some [[Some]].
    *
    * @param id
    * @param process
    * @return
    */
  def protect[A](id: ID, process: F[A]): F[Option[A]]

  def protect[A](id: ID, ifNotSeen: F[A], ifSeen: F[A]): F[A]
}

object Deduplication {

  private object field {
    val id = "id"
    val processorId = "processorId"
    val startedAt = "startedAt"
    val completedAt = "completedAt"
    val expiresOn = "expiresOn"
  }

  def nowF[F[_]: Functor: Clock] =
    Clock[F]
      .realTime(TimeUnit.MILLISECONDS)
      .map(Instant.ofEpochMilli)

  def processStatus[F[_]: Monad: Clock](
      maxProcessingTime: FiniteDuration
  )(p: Process[_, _]): F[ProcessStatus] =
    if (p.completedAt.isDefined) {
      Monad[F].point(ProcessStatus.Completed)
    } else {
      nowF[F]
        .map { now =>
          /*
           * If the startedAt is:
           *  - In the past compared to expected finishing time the processed has timeout
           *  - In the future compared to expected finishing time present the process has started but not yet completed
           */
          val isTimeout = p.startedAt
            .plus(maxProcessingTime.toJava)
            .isBefore(now)

          if (isTimeout)
            ProcessStatus.Timeout
          else
            ProcessStatus.Started
        }
    }

  private implicit def optionFormat[T](implicit f: DynamoFormat[T]) = new DynamoFormat[Option[T]] {
    def read(av: DynamoValue): Either[DynamoReadError, Option[T]] =
      read(av.toAttributeValue)

    override def read(av: AttributeValue): Either[DynamoReadError, Option[T]] =
      Option(av)
        .filter(x => !Boolean.unbox(x.isNULL))
        .map(f.read(_).map(Some(_)))
        .getOrElse(Right(Option.empty[T]))

    def write(t: Option[T]): DynamoValue = t.map(f.write).getOrElse(DynamoValue.nil)
  }

  private implicit val instantDynamoFormat: DynamoFormat[Instant] =
    DynamoFormat.coercedXmap[Instant, Long, IllegalArgumentException](Instant.ofEpochMilli)(
      _.toEpochMilli
    )

  private implicit val expirationDynamoFormat: DynamoFormat[Expiration] =
    DynamoFormat.coercedXmap[Expiration, Long, IllegalArgumentException](x =>
      Expiration(Instant.ofEpochSecond(x))
    )(_.instant.getEpochSecond)

  private implicit def processDynamoFormat[ID: DynamoFormat, ProcessorID: DynamoFormat]
      : DynamoFormat[Process[ID, ProcessorID]] = new DynamoFormat[Process[ID, ProcessorID]] {

    def read(av: DynamoValue): Either[DynamoReadError, Process[ID, ProcessorID]] =
      for {
        obj <- av.asObject
          .toRight(NoPropertyOfType("object", av))
          .leftWiden[DynamoReadError]
        id <- obj.get[ID](field.id)
        processorId <- obj.get[ProcessorID](field.processorId)
        startedAt <- obj.get[Instant](field.startedAt)
        completedAt <- obj.get[Option[Instant]](field.completedAt)
        expiresOn <- obj.get[Option[Expiration]](field.expiresOn)
      } yield Process(id, processorId, startedAt, completedAt, expiresOn)

    def write(process: Process[ID, ProcessorID]): DynamoValue =
      DynamoObject(
        field.id -> DynamoFormat[ID].write(process.id),
        field.processorId -> DynamoFormat[ProcessorID].write(process.processorId),
        field.startedAt -> DynamoFormat[Instant].write(process.startedAt),
        field.completedAt -> DynamoFormat[Option[Instant]].write(process.completedAt),
        field.expiresOn -> DynamoFormat[Option[Expiration]].write(process.expiresOn)
      ).toDynamoValue
  }

  def resource[F[_]: Async: ContextShift: Timer, ID: DynamoFormat, ProcessorID: DynamoFormat](
      config: Config[ProcessorID]
  ): Resource[F, Deduplication[F, ID]] = {
    val dynamoDbR: Resource[F, AmazonDynamoDBAsync] =
      Resource.make(Sync[F].delay(AmazonDynamoDBAsyncClientBuilder.defaultClient()))(c =>
        Sync[F].delay(c.shutdown())
      )

    dynamoDbR.map { client => Deduplication(config, client) }
  }

  def apply[F[_]: Async: ContextShift: Timer, ID: DynamoFormat, ProcessorID: DynamoFormat](
      config: Config[ProcessorID],
      client: AmazonDynamoDBAsync
  ): Deduplication[F, ID] = {

    def update(request: UpdateItemRequest) =
      Async[F]
        .async[UpdateItemResult] { cb =>
          client.updateItemAsync(
            request,
            new AsyncHandler[UpdateItemRequest, UpdateItemResult] {
              def onError(exception: Exception) = {
                cb(Left(exception))
              }

              def onSuccess(req: UpdateItemRequest, res: UpdateItemResult) = {
                cb(Right(res))
              }
            }
          )

          ()
        }
        .guarantee(ContextShift[F].shift)

    // Unfurtunately Scanamo does not support update of non existing record
    def startProcessingUpdate(
        id: ID,
        processorId: ProcessorID,
        now: Instant
    ): F[Option[Process[ID, ProcessorID]]] = {

      val result = update(
        new UpdateItemRequest()
          .withTableName(config.tableName.value)
          .withKey(
            Map(
              field.id -> DynamoFormat[ID].write(id).toAttributeValue,
              field.processorId -> DynamoFormat[ProcessorID].write(processorId).toAttributeValue
            ).asJava
          )
          .withUpdateExpression(
            s"SET ${field.startedAt} = if_not_exists(${field.startedAt}, :startedAt)"
          )
          .withExpressionAttributeValues(
            Map(
              ":startedAt" -> instantDynamoFormat.write(now).toAttributeValue
            ).asJava
          )
          .withReturnValues(ReturnValue.ALL_OLD)
      )

      result.map { res =>
        Option(res.getAttributes)
          .filter(_.size > 0)
          .map(xs => new AttributeValue().withM(xs))
          .traverse { atts =>
            DynamoFormat[Process[ID, ProcessorID]]
              .read(atts)
              .leftMap(e => new Exception(show"Error reading old item: ${e}"))
          }
      }.rethrow
    }

    new Deduplication[F, ID] {

      def tryStartProcess(id: ID): F[Sample] = {

        val pollStrategy = config.pollStrategy

        def doIt(
            startedAt: Instant,
            pollNo: Int,
            pollDelay: FiniteDuration
        ): F[Sample] = {

          def nextStep(ps: ProcessStatus) = ps match {
            case ProcessStatus.Started =>
              val totalDurationF = nowF[F]
                .map(now => (now.toEpochMilli - startedAt.toEpochMilli).milliseconds)

              // retry until it is either Completed or Timeout
              totalDurationF
                .map(td => td >= pollStrategy.maxPollDuration)
                .ifM(
                  Sync[F].raiseError(new TimeoutException(s"Stop polling after ${pollNo} polls")),
                  Timer[F].sleep(pollDelay) >> doIt(
                    startedAt,
                    pollNo + 1,
                    config.pollStrategy.nextDelay(pollNo, pollDelay)
                  )
                )
            case ProcessStatus.NotStarted | ProcessStatus.Timeout =>
              Sample.notSeen.pure[F]

            case ProcessStatus.Completed =>
              Sample.seen.pure[F]
          }

          for {
            now <- nowF[F]
            processOpt <- startProcessingUpdate(id, config.processorId, now)
            status <- processOpt
              .traverse(processStatus[F](config.maxProcessingTime))
              .map(_.getOrElse(ProcessStatus.NotStarted))
            sample <- nextStep(status)
          } yield sample
        }

        nowF[F].flatMap(now => doIt(now, 0, pollStrategy.initialDelay))
      }

      override def completeProcess(id: ID): F[Unit] = {
        for {
          now <- nowF[F]
          _ <- update(
            new UpdateItemRequest()
              .withTableName(config.tableName.value)
              .withKey(
                Map(
                  field.id -> DynamoFormat[ID].write(id).toAttributeValue,
                  field.processorId -> DynamoFormat[ProcessorID]
                    .write(config.processorId)
                    .toAttributeValue
                ).asJava
              )
              .withUpdateExpression(
                s"SET ${field.completedAt}=:completedAt, ${field.expiresOn}=:expiresOn"
              )
              .withExpressionAttributeValues(
                Map(
                  ":completedAt" -> instantDynamoFormat.write(now).toAttributeValue,
                  ":expiresOn" -> new AttributeValue()
                    .withN(now.plus(config.ttl.toJava).getEpochSecond().toString)
                ).asJava
              )
              .withReturnValues(ReturnValue.NONE)
          )
        } yield ()
      }

      override def protect[A](id: ID, ifNotSeen: F[A], ifSeen: F[A]): F[A] = {
        tryStartProcess(id)
          .flatMap(_.fold(ifNotSeen, ifSeen))
          .flatTap(_ => completeProcess(id))
      }

      override def protect[A](id: ID, process: F[A]): F[Option[A]] = {
        protect(id, process.map(_.some), none[A].pure[F])
      }
    }
  }
}

/*
 * TODO: Remove this once upgrade to later version of Scanamo
 * This method exists in 1.0.0-M12 but because of this issue:
 * https://github.com/scanamo/scanamo/issues/583, we cannot use M12 because
 * it writes List[T] to a Map if T is not a primitive type. As a result, we
 * need to use M11 which does not have this `get` method
 */
object ScanamoHelpers {
  implicit class DynObjExtras(obj: DynamoObject) {
    def get[A: DynamoFormat](key: String): Either[DynamoReadError, A] =
      obj(key).getOrElse(DynamoValue.nil).as[A]
  }
}
