package io.github.oxlade39.storrent.peer

import org.scalatest.{WordSpecLike, BeforeAndAfterAll, WordSpec, FunSuite}
import io.github.oxlade39.storrent.test.util.{ForwardingParent, FileOps}
import org.scalatest.matchers.MustMatchers
import akka.testkit.{TestProbe, TestKit, ImplicitSender}
import akka.actor.{Actor, Props, ActorSystem}
import org.scalatest.mock.MockitoSugar
import io.github.oxlade39.storrent.core.Torrent
import java.net.InetSocketAddress
import io.github.oxlade39.storrent.piece.PieceManager
import org.mockito.Mockito
import concurrent.duration._
import akka.util.ByteString
import io.github.oxlade39.storrent.peer.DownloadWorkPullingPattern.DownloadTask

/**
 * @author dan
 */
class Downloader2Test extends TestKit(ActorSystem("Downloader2Test"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps with MockitoSugar {

  override def afterAll() = {
    system.shutdown()
  }

  "WorkFetcher" must {
    "fetch work and send to queue, ordered by rarest" in {
      val pieceCount = 100
      //      val torrent: Torrent = mock[Torrent]
      //      Mockito.when(torrent.pieceCount).thenReturn(pieceCount)
      val pieceManager, peerManager, workQueue, peerRef0, peerRef1 = TestProbe()
      val peerOne, peerTwo = Peer(new InetSocketAddress(0))

      val underTest = 
        system.actorOf(Props(new WorkFetcher(peerManager.ref, pieceManager.ref, workQueue.ref)))

      peerManager.expectMsg(PeerManager.GetConnectedPeers)
      peerManager.reply(PeerManager.ConnectedPeers(Map(
        peerRef0.ref -> peerOne,
        peerRef1.ref -> peerTwo
      )))
      
      pieceManager.expectMsg(PieceManager.GetPeerPieceMappings)
      pieceManager.reply(PieceManager.PeerPieceMappings(
        global = PieceManager.Pieces(size = pieceCount, Set(0, 1, 2, 3)),
        localPieces = PieceManager.Pieces(size = pieceCount),
        pieceCounts = Map(
          0 -> Set(peerOne.id),
          1 -> Set(peerOne.id, peerTwo.id),
          2 -> Set(peerOne.id),
          4 -> Set(peerOne.id, peerTwo.id)
        ))
      )
      
      peerRef0.expectMsg(PeerProtocol.GetPeerStatus)
      peerRef1.expectMsg(PeerProtocol.GetPeerStatus)
      
      val peer0LocalStatus = PeerProtocol.PeerStatus(choked = false, interested = true, pieces = Bitfield(Seq()))
      val peer0RemoteStatus = PeerProtocol.PeerStatus(pieces = Bitfield(Seq()))
      val peer1LocalStatus = PeerProtocol.PeerStatus(choked = false, interested = true, pieces = Bitfield(Seq()))
      val peer1RemoteStatus = PeerProtocol.PeerStatus(pieces = Bitfield(Seq()))
      peerRef0.reply((peer0LocalStatus, peer0RemoteStatus))
      peerRef1.reply((peer1LocalStatus, peer1RemoteStatus))
      
      val DownloadWorkPullingPattern.CurrentWork(work) = workQueue.expectMsgType[DownloadWorkPullingPattern.CurrentWork]
      work.size mustEqual 4
      work.head._1 must (be(0) or be(2))
      work.head._2 must (
        be(Set(DownloadWorkPullingPattern.DownloadTask(peerRef0.ref, 0)))
          or
          be(Set(DownloadWorkPullingPattern.DownloadTask(peerRef0.ref, 2)))
      )
    }
  }

  "WorkQueue" must {
    import Downloader2Test._

    "response with NoWorkAvailable when queue is empty" in {
      val underTest = system.actorOf(Props[WorkQueue])
      underTest ! DownloadWorkPullingPattern.RequestWork
      expectMsg(DownloadWorkPullingPattern.NoWorkAvailable)
    }

    "respond with work queued in order" in {

      val (peerRefOne, peerRefTwo, currentWork) = workQueueSetup()

      val underTest = system.actorOf(Props[WorkQueue])

      underTest ! currentWork

      underTest ! DownloadWorkPullingPattern.RequestWork
      expectMsg(currentWork.work.head._2.head)
    }

    "filter out work which is currently being worked on" in {
      val (peerRefOne, peerRefTwo, currentWork) = workQueueSetup()

      val underTest = system.actorOf(Props[WorkQueue])

      underTest ! currentWork
      underTest ! DownloadWorkPullingPattern.RequestWork
      expectMsgType[DownloadWorkPullingPattern.DownloadTask]
      underTest ! DownloadWorkPullingPattern.RequestWork

      val nextWork = expectMsgType[DownloadWorkPullingPattern.DownloadTask]
      nextWork must (
        be(DownloadWorkPullingPattern.DownloadTask(peerRefTwo.ref, 1))
          or
        be(DownloadWorkPullingPattern.DownloadTask(peerRefTwo.ref, 3))
      )
    }

    "no longer filter out work from a peer after a worker notifies queue of completion on that peer" in {
      val (peerRefOne, peerRefTwo, currentWork) = workQueueSetup()

      val underTest = system.actorOf(Props[WorkQueue])

      underTest ! currentWork
      underTest ! DownloadWorkPullingPattern.RequestWork
      underTest ! DownloadWorkPullingPattern.TaskDone(expectMsgType[DownloadWorkPullingPattern.DownloadTask])
      underTest ! DownloadWorkPullingPattern.RequestWork

      val nextWork = expectMsgType[DownloadWorkPullingPattern.DownloadTask]
      nextWork mustEqual DownloadWorkPullingPattern.DownloadTask(peerRefOne.ref, 2)
    }

    "allow task to be retried if worker has previously failed on it" in {
      val (peerRefOne, peerRefTwo, currentWork) = workQueueSetup()

      val underTest = system.actorOf(Props[WorkQueue])

      underTest ! currentWork
      underTest ! DownloadWorkPullingPattern.RequestWork
      val originalTask = expectMsgType[DownloadWorkPullingPattern.DownloadTask]
      underTest ! DownloadWorkPullingPattern.TaskFailed(originalTask)

      underTest ! DownloadWorkPullingPattern.RequestWork
      expectMsg(originalTask)
    }
  }

  "DownloadWorker" must {
    "request work on initialisation" in {
      val workQueue, pieceManager = TestProbe()
      val torrent = mock[Torrent]

      system.actorOf(Props(new DownloadWorker(workQueue.ref, pieceManager.ref, torrent, 100.millis)))
      workQueue.expectMsg(DownloadWorkPullingPattern.RequestWork)
    }

    "continue to requests work if none sent" in {
      val workQueue, pieceManager = TestProbe()
      val torrent = mock[Torrent]

      system.actorOf(Props(new DownloadWorker(workQueue.ref, pieceManager.ref, torrent, 50.millis)))
      workQueue.receiveN(5, (6 * 50).millis)
    }

    "when given work, create a child PieceDownloader for the given peerRef" in {
      val workQueue, peerRef, fakePieceDownloader, pieceManager = TestProbe()
      val torrent = mock[Torrent]
      val pieceIndex = 0
      val pieceCount = 100
      val pieceSize = 50

      Mockito.when(torrent.pieceCount).thenReturn(pieceCount)
      Mockito.when(torrent.pieceSize).thenReturn(pieceSize)
      Mockito.when(torrent.pieceHashes).thenReturn(List(ByteString("some hash")))

      val worker = system.actorOf(Props(new DownloadWorker(workQueue.ref, pieceManager.ref, torrent, 1.minutes) {
        override def pieceDownloader(task: DownloadTask) = Props(new Actor {
          def receive = {case msg => fakePieceDownloader.ref forward msg}
        })
      }))
      workQueue.expectMsg(DownloadWorkPullingPattern.RequestWork)
      workQueue.reply(DownloadWorkPullingPattern.DownloadTask(peerRef.ref, pieceIndex = pieceIndex))
      fakePieceDownloader.send(worker, PieceDownloader.Success(DownloadPiece(
        pieceIndex,
        pieceSize,
        offset = pieceIndex * pieceSize,
        hash = ByteString("some hash")
      )))
      workQueue.expectMsg(DownloadWorkPullingPattern.TaskDone(DownloadWorkPullingPattern.DownloadTask(
        peerRef = peerRef.ref, pieceIndex = pieceIndex
      )))
    }
  }

}

object Downloader2Test {

  def workQueueSetup()(implicit system: ActorSystem): (TestProbe, TestProbe, DownloadWorkPullingPattern.CurrentWork) = {
    val peerRefOne, peerRefTwo = TestProbe()
    val currentWork =
      DownloadWorkPullingPattern.CurrentWork(Seq(
        (0, Set(
          DownloadWorkPullingPattern.DownloadTask(peerRefOne.ref, 0)
        )),
        (2, Set(
          DownloadWorkPullingPattern.DownloadTask(peerRefOne.ref, 2)
        )),
        (1, Set(
          DownloadWorkPullingPattern.DownloadTask(peerRefOne.ref, 1),
          DownloadWorkPullingPattern.DownloadTask(peerRefTwo.ref, 1)
        )),
        (3, Set(
          DownloadWorkPullingPattern.DownloadTask(peerRefOne.ref, 3),
          DownloadWorkPullingPattern.DownloadTask(peerRefTwo.ref, 3)
        ))
      ))
    (peerRefOne, peerRefTwo, currentWork)
  }

}
