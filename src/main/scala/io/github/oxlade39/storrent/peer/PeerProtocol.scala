package io.github.oxlade39.storrent.peer

import akka.actor._
import akka.event.LoggingReceive
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.ByteString
import io.github.oxlade39.storrent.core.Torrent

object PeerProtocol {

  def props(torrent: Torrent) = Props(new PeerProtocol(torrent))

  case object GetPeerStatus

  case class PeerStatus(choked: Boolean = true, interested: Boolean = false, pieces: Bitfield) {
    def unchoke: PeerStatus = copy(choked = false)
    def choke: PeerStatus = copy(choked = true)
    def interested_! : PeerStatus = copy(interested = true)
    def notInterested: PeerStatus = copy(interested = false)
  }
}

class PeerProtocol(torrent: Torrent) extends Actor with ActorLogging {
  import PeerConnection._
  import PeerProtocol._

  import concurrent.duration._

  var localPeer, remotePeer = PeerStatus(pieces = Bitfield(torrent.pieceHashes.map(_ => false)))

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
      log.debug("peer is interested in our pieces, we should unchoke at some point")
      remotePeer = remotePeer.interested_!

    case Received(bf: Bitfield) =>
      remotePeer = remotePeer.copy(pieces = bf)
      context.parent ! remotePeer

    case Received(Have(pieceIndex)) =>
      val updatedBitfield = remotePeer.pieces.set(pieceIndex)
      remotePeer = remotePeer.copy(pieces = updatedBitfield)
      context.parent ! remotePeer

    case Send(NotInterested) =>
      localPeer = localPeer.notInterested

    case Send(Interested) =>
      localPeer = localPeer.interested_!

    case Send(Choke) =>
      remotePeer = remotePeer.choke

    case Send(UnChoke) =>
      remotePeer = remotePeer.unchoke

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
 * Proxy in front of client peer.
 *
 * @param messageProcessor actor to receive Message instances
 *                         (this will typically be handler for the client peers messages)
 * @param bytesProcessor actor to receive ByteString instances
 *                       (this will typically be the client peer's connection actor)
 */
class PeerProtocolProcessor(bytesProcessor: ActorRef,
                            messageProcessor: ActorRef)
  extends Actor with ActorLogging {

  import Pipeline._
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val messageQueue: SourceQueueWithComplete[Message] =
    Source.queue[Message](100, OverflowStrategy.backpressure)
    .map(m => PartialMessage(m.length, m.messageId, m.payload.getOrElse(ByteString.empty)))
    .map{pm =>
      val bb = ByteString.newBuilder.putLongPart(pm.length, lengthBytes)
      pm.messageId.foreach(mId => bb.putLongPart(mId, idBytes))
      bb ++= pm.body
      bb.result()
    }
    .toMat(Sink.foreach(bytes => bytesProcessor ! bytes))(Keep.left)
    .run()

  val bytesQueue: SourceQueueWithComplete[ByteString] =
    ReactiveMessageParsing.byteStringParserQueue
    .toMat(Sink.foreach(msg => messageProcessor ! msg))(Keep.left)
    .run()

  def receive = LoggingReceive {
    case m: Message => messageQueue.offer(m)
    case b: ByteString => bytesQueue.offer(b)
  }
}
