package com.sope.spark.yaml

import com.sope.common.yaml.MapYaml
import org.apache.spark.sql.types.StructType

/**
  * Yaml file representing Spark SQL Schema
  *
  * @author mbadgujar
  */
case class SchemaYaml(schemaFile: String) extends MapYaml[String, String](schemaFile) {

  def getSparkSchema: StructType = StructType.fromDDL {
    logInfo(s"Generating Spark Schema from provided configuration: $model")
    model.map { case (k, v) => k + " " + v }.mkString(",")
  }
}
