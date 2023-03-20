package org.apache.openwhisk.core.Test

import org.scalatest.Matchers
import org.scalatest.Suite
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.ArgNormalizer.trim
import org.apache.openwhisk.core.entity.ExecManifest._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.loadBalancer.StreamLogging
import spray.json._
import spray.json.DefaultJsonProtocol._

trait ExecHelpers extends Matchers with WskActorSystem with StreamLogging {
  self: Suite =>

  private val config = new WhiskConfig(ExecManifest.requiredProperties)
  print(config.wskApiHost)
  ExecManifest.initialize(config) should be a 'success

  protected val NODEJS = "nodejs:14"
  protected val SWIFT5 = "swift:5.3"
  protected val BLACKBOX = "blackbox"
  protected val JAVA_DEFAULT = "java:8"

  private def attFmt[T: JsonFormat] = Attachments.serdes[T]

  protected def imageName(name: String) =
    ExecManifest.runtimesManifest.runtimes.flatMap(_.versions).find(_.kind == name).get.image

  protected def jsOld(code: String, main: Option[String] = None) = {
    CodeExecAsString(
      RuntimeManifest(
        NODEJS,
        imageName(NODEJS),
        default = Some(true),
        deprecated = Some(false),
        stemCells = Some(List(StemCell(2, 256.MB)))),
      trim(code),
      main.map(_.trim))
  }

  protected def js(code: String, main: Option[String] = None) = {
    val attachment = attFmt[String].read(code.trim.toJson)
    val manifest = ExecManifest.runtimesManifest.resolveDefaultRuntime(NODEJS).get

    CodeExecAsAttachment(manifest, attachment, main.map(_.trim), Exec.isBinaryCode(code))
  }

  protected def jsDefault(code: String, main: Option[String] = None) = {
    js(code, main)
  }

  protected def jsMetaDataOld(main: Option[String] = None, binary: Boolean) = {
    CodeExecMetaDataAsString(
      RuntimeManifest(
        NODEJS,
        imageName(NODEJS),
        default = Some(true),
        deprecated = Some(false),
        stemCells = Some(List(StemCell(2, 256.MB)))),
      binary,
      main.map(_.trim))
  }

  protected def jsMetaData(main: Option[String] = None, binary: Boolean) = {
    val manifest = ExecManifest.runtimesManifest.resolveDefaultRuntime(NODEJS).get

    CodeExecMetaDataAsAttachment(manifest, binary, main.map(_.trim))
  }

  protected def javaDefault(code: String, main: Option[String] = None) = {
    val attachment = attFmt[String].read(code.trim.toJson)
    val manifest = ExecManifest.runtimesManifest.resolveDefaultRuntime(JAVA_DEFAULT).get

    CodeExecAsAttachment(manifest, attachment, main.map(_.trim), Exec.isBinaryCode(code))
  }

  protected def javaMetaData(main: Option[String] = None, binary: Boolean) = {
    val manifest = ExecManifest.runtimesManifest.resolveDefaultRuntime(JAVA_DEFAULT).get

    CodeExecMetaDataAsAttachment(manifest, binary, main.map(_.trim))
  }

  protected def swift(code: String, main: Option[String] = None) = {
    val attachment = attFmt[String].read(code.trim.toJson)
    val manifest = ExecManifest.runtimesManifest.resolveDefaultRuntime(SWIFT5).get

    CodeExecAsAttachment(manifest, attachment, main.map(_.trim), Exec.isBinaryCode(code))
  }

  protected def sequence(components: Vector[FullyQualifiedEntityName]) = SequenceExec(components)

  protected def sequenceMetaData(components: Vector[FullyQualifiedEntityName]) = SequenceExecMetaData(components)

  protected def bb(image: String) = BlackBoxExec(ExecManifest.ImageName(trim(image)), None, None, false, false)

  protected def bb(image: String, code: String, main: Option[String] = None) = {
    val (codeOpt, binary) =
      if (code.trim.nonEmpty) (Some(attFmt[String].read(code.toJson)), Exec.isBinaryCode(code))
      else (None, false)
    BlackBoxExec(ExecManifest.ImageName(trim(image)), codeOpt, main, false, binary)
  }

  protected def blackBoxMetaData(image: String, main: Option[String] = None, binary: Boolean) = {
    BlackBoxExecMetaData(ExecManifest.ImageName(trim(image)), main, false, binary)
  }

  protected def actionLimits(memory: ByteSize, concurrency: Int): ActionLimits =
    ActionLimits(memory = MemoryLimit(memory), concurrency = ConcurrencyLimit(concurrency))
}

