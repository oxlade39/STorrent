package io.github.oxlade39.storrent.piece

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.peer.{Bitfield, PeerId}

/**
 * @author dan
 */
class PieceManagerTest extends TestKit(ActorSystem("PeerConnectionTest"))
with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers {
  import io.github.oxlade39.storrent.test.util.Files._
  import PieceManager._

  "PieceManager" should {
    "By default there are no pieces" in {

      val pieceManager = system.actorOf(PieceManager.props(ubuntuTorrent))
      pieceManager ! GetPeerPieceMappings

      val mappings = expectMsgType[PeerPieceMappings]
      mappings.mappings mustEqual Map.empty[PeerId, Pieces]
      mappings.global mustEqual Pieces(ubuntuTorrent.pieceCount)
    }

    "keeps track of which peers have which pieces" in {
      val peerOne, peerTwo = PeerId()
      val pieceManager = system.actorOf(PieceManager.props(ubuntuTorrent))

      pieceManager ! PeerHasPieces(peerOne, Bitfield(ubuntuTorrent.pieceHashes.map(_ => false)).set(2))
      pieceManager ! PeerHasPieces(peerTwo, Bitfield(ubuntuTorrent.pieceHashes.map(_ => false)).set(23))
      pieceManager ! GetPeerPieceMappings

      val mappings = expectMsgType[PeerPieceMappings]

      mappings.mappings mustEqual Map(
        peerOne -> Pieces(ubuntuTorrent.pieceCount, Set(2)),
        peerTwo -> Pieces(ubuntuTorrent.pieceCount, Set(23))
      )
      mappings.global mustEqual Pieces(ubuntuTorrent.pieceCount, Set(2, 23))
    }
  }
}

object PieceManagerTest {

}

class PeerPieceMappingsTest extends WordSpecLike with MustMatchers {
  import PieceManager._

  "PeerPieceMappings" must {
    "append" in {
      val mappings =
        PeerPieceMappings(Pieces(10)) ++
          (PeerId("has 0"), Pieces(10, Set(0))) ++
          (PeerId("has 5"), Pieces(10, Set(5))) ++
          (PeerId("has 9"), Pieces(10, Set(9)))

      mappings mustEqual PeerPieceMappings(
        Pieces(10, Set(0,5,9)),
        Map(
          PeerId("has 0") -> Pieces(10, Set(0)),
          PeerId("has 5") -> Pieces(10, Set(5)),
          PeerId("has 9") -> Pieces(10, Set(9))
        )
      )
    }
  }
}