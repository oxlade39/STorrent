package io.github.oxlade39.storrent.workpulling

import akka.actor.{ActorLogging, Terminated, ActorRef, Actor}
import scala.collection.immutable.Queue
import scala.reflect.ClassTag
import scala.concurrent.Future
import scala.util.{Failure, Success}

object WorkPullingPattern {
  sealed trait Message

  case object RequestWork extends Message
  case object Busy extends Message
  case object WorkAvailable extends Message
  case object WorkDone extends Message
  case object WorkFailed extends Message
  case class Work[T](work: T) extends Message
  case class RegisterWorker(worker: ActorRef)
}

class Master[T: ClassTag] extends Actor with ActorLogging {
  import WorkPullingPattern._

  var workQueue = Queue.empty[T]
  var workers = Set.empty[ActorRef]
  var pendingWork = Map.empty[ActorRef, Work[T]]

  def receive = {
    case t: collection.immutable.Iterable[T] =>
      workQueue = workQueue.enqueue(t)
      workers foreach (_ ! WorkAvailable)

    case t: T =>
      workQueue = workQueue enqueue t
      workers foreach (_ ! WorkAvailable)

    case RegisterWorker(worker) =>
      workers += context.watch(worker)

    case Terminated(worker) =>
      workers -= worker

    case WorkDone =>
      pendingWork -= sender

    case RequestWork =>
      if (workQueue.isEmpty) log.info("no work available for work requester {}", sender)
      else {
        val (head, tail) = workQueue.dequeue
        workQueue = tail
        pendingWork += (sender -> Work(head))
        sender ! Work(head)
      }
  }
}

abstract class Worker[T: ClassTag, F](val master: ActorRef) extends Actor with ActorLogging {
  import WorkPullingPattern._
  implicit val ec = context.dispatcher

  override def preStart() = {
    master ! RegisterWorker(self)
    master ! RequestWork
  }

  def receive = {
    case WorkAvailable =>
      master ! RequestWork

    case Work(work: T) =>
      doWork(work) onComplete {
        case Success(_) => master ! WorkDone; master ! RequestWork
        case Failure(t) => {
          log.error(t, t.getMessage)
          master ! WorkFailed
          master ! RequestWork
        }
      }

  }

  def doWork(work: T): Future[F]
}