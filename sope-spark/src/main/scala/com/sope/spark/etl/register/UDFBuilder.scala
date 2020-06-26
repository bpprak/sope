package com.sope.spark.etl.register

import java.io.File
import java.net.URLClassLoader

import com.sope.etl.getObjectInstance
import com.sope.common.transform.exception.TransformException
import com.sope.common.utils.JarUtils
import com.sope.utils.Logging
import org.apache.commons.io.FileUtils
import org.apache.spark.sql.expressions.UserDefinedFunction

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.IMain

object  UDFBuilder extends Logging {

  val DefaultClassLocation = "/tmp/sope/dynamic/"
  val DefaultJarLocation = "/tmp/sope/sope-dynamic-udf.jar"


  /*
     Returns 'object' code to be compiled
   */
  private def objectString(clazz: String, code: String) =
    s"""
       | package com.sope.etl.dynamic
       |
       | import org.apache.spark.sql.functions._
       | import org.apache.spark.sql.expressions.UserDefinedFunction
       |
       | object $clazz  {
       |   def getUDF : UserDefinedFunction = udf($code)
       | }
       |
    """.stripMargin

  /*
     Compiles the UDF code
   */
  private def evalUDF(udfCodeMap: Map[String, String]): Map[String, UserDefinedFunction] = {
    val settings = new Settings
    settings.Yreploutdir.value = DefaultClassLocation
    val systemClasspath = System.getProperty("java.class.path").split(File.pathSeparator)
    val contextClasspath = this.getClass.getClassLoader.asInstanceOf[URLClassLoader].getURLs.map(_.toString)
    val builderClassPath = (systemClasspath ++ contextClasspath).distinct.mkString(File.pathSeparator)
    logDebug(s"UDF Builder Classpath :- $builderClassPath")
    settings.classpath.value = builderClassPath
    val eval = new IMain(settings)
    val udfMap = udfCodeMap.map {
      case (udfName, udfCode) =>
        val objectCode = objectString(udfName, udfCode)
        logDebug(s"UDF code to be compiled: $objectCode")
        if (!eval.compileString(objectCode)) {
          logError("Failed to compile UDF code")
          throw new TransformException(s"Failed to build $udfName UDF")
        }
        val instance = getObjectInstance[Any](eval.classLoader, "com.sope.etl.dynamic." + udfName)
        udfName -> instance.getClass.getDeclaredMethod("getUDF").invoke(instance).asInstanceOf[UserDefinedFunction]
    }
    eval.close()
    udfMap
  }

  /**
    * Builds UDFs provided as scriptlets in Yaml file.
    * A jar file is also created, which is distributed to executors
    *
    * @param udfCodeMap Map of udf name to be register with [[org.apache.spark.sql.SQLContext]] and udf code
    * @return [[Map]] of UDF name and [[UserDefinedFunction]]
    */
  def buildDynamicUDFs(udfCodeMap: Map[String, String]): Map[String, UserDefinedFunction] = {
    val file = new java.io.File(UDFBuilder.DefaultClassLocation)
    FileUtils.deleteDirectory(file)
    file.mkdirs()
    val udfMap = evalUDF(udfCodeMap)
    JarUtils.buildJar(DefaultClassLocation, DefaultJarLocation)
    udfMap
  }

}