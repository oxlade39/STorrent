package io.github.oxlade39.storrent.test.util

import java.io.File
import io.github.oxlade39.storrent.core.Torrent

trait FileOps {

  implicit class FileStringOps(s: String) {
    def asFile:File = file(s)

    def /(child: String) = new File(file(s), child)
  }

  def file(location: String) =
    new File(Thread.currentThread().getContextClassLoader.getResource(location).toURI)
}

object Files extends FileOps {
  val ubuntuTorrent: Torrent = Torrent("examples" / "ubuntu.torrent")
}