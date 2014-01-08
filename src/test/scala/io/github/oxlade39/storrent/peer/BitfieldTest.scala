package io.github.oxlade39.storrent.peer

import org.scalatest.WordSpecLike
import org.scalatest.matchers.MustMatchers

/**
 * @author dan
 */
class BitfieldTest extends WordSpecLike with MustMatchers {
  import io.github.oxlade39.storrent.test.util.Files.ubuntuTorrent

  "Bitfield" must {
    "pad up to multiple of 8" in {
      Bitfield.padded(true) mustEqual Bitfield(Seq(true) ++ Seq.fill(7)(false))
      Bitfield.padded(true, false) mustEqual Bitfield(Seq(true, false) ++ Seq.fill(6)(false))

      Bitfield(Seq.fill(8)(true) ++ Seq(true, false, true)).padded mustEqual
        Bitfield(Seq.fill(8)(true) ++ Seq(true, false, true) ++ Seq.fill(5)(false))
    }

    "trims to numbers of pieces in torrent" in {
      val bitfieldWithCorrectNumberOfPieces = Bitfield(Seq.fill(ubuntuTorrent.pieceCount)(true))
      val paddedThenTrimmed = bitfieldWithCorrectNumberOfPieces.padded.trimmed(ubuntuTorrent)

      bitfieldWithCorrectNumberOfPieces mustEqual paddedThenTrimmed
    }
  }
}
