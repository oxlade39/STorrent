package io.github.oxlade39.storrent.peer

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import org.scalatest.MustMatchers
import io.github.oxlade39.storrent.test.util.{RandomOps, ForwardingParent, FileOps}
import akka.util.ByteString
import concurrent.duration._
import scala.util.Random
import io.github.oxlade39.storrent.core.Torrent

class PieceDownloaderTest extends TestKit(ActorSystem("PieceDownloaderTest"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps with RandomOps{

  "PieceDownloader" must {

    "request pieces in parallel from peer" in  {
      val peer = TestProbe()
      val maxPendingRequests = 3
      val requestLength = 10
      val initialPiece = DownloadPiece(index = 5,
        size = 112,
        offset = 5 * 112,
        hash = ByteString("hash"))

      val pd = system.actorOf(PieceDownloader.props(
        peer.ref, initialPiece, maxPendingRequests, requestLength
      ))

      val requests = 0.until(maxPendingRequests).map(_ => peer.expectMsgPF(5.minutes) {
        case PeerConnection.Send(r: Request) => r
      })

      0.until(maxPendingRequests).foreach { i =>
        requests.exists(r => r.index == 5 && r.begin == i * requestLength) mustEqual true
      }
    }

    "requests more blocks once requested blocks come back successfully" in {
      val peer = TestProbe()
      val maxPendingRequests = 3
      val requestLength = 10
      val initialPiece = DownloadPiece(index = 5,
        size = 112,
        offset = 5 * 112,
        hash = ByteString("hash"))

      val pd = system.actorOf(PieceDownloader.props(
        peer.ref, initialPiece, maxPendingRequests, requestLength
      ))

      peer.receiveN(maxPendingRequests)
      peer.reply(Piece(pieceIndex = 5, begin = 0, block = Random.nextByteString(requestLength)))
      peer.expectMsg(PeerConnection.Send(
        Request(index = 5,
                begin = maxPendingRequests * requestLength,
                requestLength = requestLength)
        )
      )
      // pieces don't necessarily come back in order
      peer.reply(
        Piece(pieceIndex = 5,
                 begin = maxPendingRequests * requestLength,
                 block = Random.nextByteString(requestLength)))
      peer.expectMsg(PeerConnection.Send(
        Request(index = 5,
                begin = (maxPendingRequests + 1) * requestLength,
                requestLength = requestLength)
        )
      )

    }

    "once all blocks in the piece are received and the hash matches the PieceDownload is done" in {
      val peer = TestProbe()
      val maxPendingRequests = 3
      val requestLength = 10
      val pieceBytes = ByteString(
        s"""
          |Some bytes that will be split up into length of $requestLength.
          |Hello Torrent world!
        """.stripMargin
      )
      val totalPieceSize: Int = pieceBytes.size
      val initialPiece = DownloadPiece(index = 5,
        size = totalPieceSize,
        offset = 5 * totalPieceSize,
        hash = Torrent.hash(pieceBytes))

      val pd = watch(system.actorOf(Props(new ForwardingParent(PieceDownloader.props(
        peer.ref, initialPiece, maxPendingRequests, requestLength
      ), testActor))))

      val pieces = for {
        (blockBytes, index) <- pieceBytes.grouped(requestLength).zipWithIndex
      } yield Piece(5, index * requestLength, blockBytes)

      pieces foreach { p =>
        peer.expectMsgPF(){ case PeerConnection.Send(r: Request) => r }
        peer.reply(p)
      }

      val success = expectMsgType[PieceDownloader.Success]
      expectTerminated(pd)

      success.downloadPiece.contiguousStream mustEqual Some(pieceBytes)
    }

    "reject the piece if the hash doesn't match" in {
      val peer = TestProbe()
      val maxPendingRequests = 3
      val requestLength = 10
      val pieceBytes = ByteString(
        s"""
          |Some bytes that will be split up into length of $requestLength.
          |Hello Torrent world!
        """.stripMargin
      )
      val totalPieceSize: Int = pieceBytes.size
      val initialPiece = DownloadPiece(index = 5,
        size = totalPieceSize,
        offset = 5 * totalPieceSize,
        hash = ByteString("bad hash"))

      val pd = watch(system.actorOf(Props(new ForwardingParent(PieceDownloader.props(
        peer.ref, initialPiece, maxPendingRequests, requestLength
      ), testActor))))

      val pieces = for {
        (blockBytes, index) <- pieceBytes.grouped(requestLength).zipWithIndex
      } yield Piece(5, index * requestLength, blockBytes)

      pieces foreach { p =>
        peer.expectMsgPF(){ case PeerConnection.Send(r: Request) => r }
        peer.reply(p)
      }

      expectTerminated(pd, 5.minutes)
    }
  }

}
