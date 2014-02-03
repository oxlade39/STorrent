package io.github.oxlade39.storrent.peer

import akka.actor._
import akka.io.{PipelineFactory, PipelineContext}
import akka.event.LoggingReceive
import akka.util.ByteString

object PeerProtocol {

  def props = Props(new PeerProtocol)

  case object GetPeerStatus

  case class PeerStatus(choked: Boolean = true, interested: Boolean = false, pieces: Option[Bitfield] = None) {
    def unchoke = copy(choked = false)
    def choke = copy(choked = true)
    def interested_! = copy(interested = true)
    def notInterested = copy(interested = false)
  }
}

class PeerProtocol extends Actor with ActorLogging {
  import PeerProtocol._
  import PeerConnection._
  import concurrent.duration._

  var localPeer = PeerStatus()
  var remotePeer = PeerStatus()

  var activeDownloader = Option.empty[ActorRef]

  context.setReceiveTimeout(2.minutes - 1.second)

  def receive = LoggingReceive {
    case ReceiveTimeout =>
      context.parent ! Send(KeepAlive)

    case Received(UnChoke) =>
      localPeer = localPeer.unchoke

    case Received(Choke) =>
      localPeer = localPeer.choke

    case Received(KeepAlive) =>

    case Received(NotInterested) =>
      remotePeer = remotePeer.notInterested

    case Received(Interested) =>
      remotePeer = remotePeer.interested_!

    case Received(bf: Bitfield) =>
      remotePeer = remotePeer.copy(pieces = Some(bf))
      context.parent ! remotePeer

    case Received(Have(pieceIndex)) if remotePeer.pieces.isDefined =>
      val updatedBitFieldOption = remotePeer.pieces.map(current => current.set(pieceIndex))
      remotePeer = remotePeer.copy(pieces = updatedBitFieldOption)
      context.parent ! remotePeer

    case Send(NotInterested) =>
      localPeer = localPeer.notInterested

    case Send(Interested) =>
      localPeer = localPeer.interested_!

    case Send(Request(index, _, _)) =>
      if (activeDownloader.isDefined && activeDownloader.get != sender)
        log.warning("overwriting existing downloader {}", activeDownloader.get)
      activeDownloader = Some(context.watch(sender))

    case Send(other) =>
      log.debug("ignoring send of {} as has no state implication", other)

    case Received(completedPiece: Piece) =>
      if (activeDownloader.isEmpty) log.warning("received {} but no active downloader", completedPiece.pieceIndex)
      activeDownloader foreach (_ ! completedPiece)

    case Terminated(finishedDownloader) =>
      activeDownloader = None

    case GetPeerStatus =>
      sender ! (localPeer, remotePeer)
  }
}

object PeerProtocolProcessor {
  def props(bytesProcessor: ActorRef, messageProcessor: ActorRef) =
    Props(new PeerProtocolProcessor(bytesProcessor, messageProcessor))
}

/**
 * Proxy infront of client peer.
 *
 * @param messageProcessor actor to receive Message instances
 *                         (this will typically be handler for the client peers messages)
 * @param bytesProcessor actor to receive ByteString instances
 *                       (this will typically be the client peer's connection actor)
 */
class PeerProtocolProcessor(bytesProcessor: ActorRef,
                            messageProcessor: ActorRef)
  extends Actor with ActorLogging {

  val ctx = new PipelineContext {}

  val pipeline =
    PipelineFactory.buildWithSinkFunctions(ctx, new MessageStage >> new MessageTypeStage)(
      cmd => bytesProcessor ! cmd.get,
      evt => messageProcessor ! evt.get
    )

  def receive = LoggingReceive {
    case m: Message => pipeline.injectCommand(m)
    case b: ByteString => pipeline.injectEvent(b)
  }
}