package com.hamstoo.services

import com.whisk.docker.{DockerContainer, DockerKit}

trait DockerVectorService extends DockerKit {

  val vectorImage = "519312307223.dkr.ecr.us-east-1.amazonaws.com/conceptnet-vectors"
  val vectorPort = 5000

  val vectorContainer: DockerContainer = DockerContainer(vectorImage)
    .withPorts(vectorPort -> None)
    .withEnv("PYTHONPATH=/app/src_mountpoint/conceptnet5")

  abstract override def dockerContainers: List[DockerContainer] =
    vectorContainer :: super.dockerContainers
}
