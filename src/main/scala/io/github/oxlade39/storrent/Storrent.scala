package io.github.oxlade39.storrent

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import java.io.File

object Storrent {
  case class DownloadTorrent(file: File)
  case class StopDownloading(file: File)
  case class PauseDownloading(file: File)
}

class Storrent extends Actor with ActorLogging {
  import Storrent._
  import context._

  var activeDownloads = Map.empty[File, ActorRef]

  def receive = {
    case DownloadTorrent(file) if !activeDownloads.contains(file) =>
      activeDownloads += (file -> watch(actorOf(StorrentDownload.props(file))))
  }
}

object StorrentDownload {
  def props(file: File): Props = Props(new StorrentDownload(file))

}

class StorrentDownload(file: File) extends Actor with ActorLogging {
  def receive = ???
}
