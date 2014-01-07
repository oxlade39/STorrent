package io.github.oxlade39.storrent.peer

import org.scalatest.WordSpecLike
import org.scalatest.matchers.MustMatchers

/**
 * @author dan
 */
class BitfieldTest extends WordSpecLike with MustMatchers {
  "Bitfield" must {
    "pad up to multiple of 8" in {
      Bitfield.padded(Seq(true)) mustEqual Bitfield(Seq(true) ++ Seq.fill(7)(false))
      Bitfield.padded(Seq(true, false)) mustEqual Bitfield(Seq(true, false) ++ Seq.fill(6)(false))

      Bitfield.padded(Seq.fill(8)(true) ++ Seq(true, false, true)) mustEqual
        Bitfield(Seq.fill(8)(true) ++ Seq(true, false, true) ++ Seq.fill(5)(false))
    }
  }
}
