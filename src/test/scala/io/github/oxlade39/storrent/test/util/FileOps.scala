package io.github.oxlade39.storrent.test.util

import java.io.File

trait FileOps {

  implicit class FileStringOps(s: String) {
    def asFile:File = file(s)

    def /(child: String) = new File(file(s), child)
  }

  def file(location: String) =
    new File(Thread.currentThread().getContextClassLoader.getResource(location).toURI)
}
