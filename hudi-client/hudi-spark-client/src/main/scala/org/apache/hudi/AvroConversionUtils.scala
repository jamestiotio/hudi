/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.avro.generic.GenericRecord
import org.apache.avro.{JsonProperties, Schema}
import org.apache.hudi.HoodieSparkUtils.sparkAdapter
import org.apache.hudi.avro.AvroSchemaUtils
import org.apache.hudi.internal.schema.HoodieSchemaException
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.types.{ArrayType, DataType, MapType, StructType}
import org.apache.spark.sql.{Dataset, Row, SparkSession}

import scala.collection.JavaConversions._

object AvroConversionUtils {

  /**
   * Creates converter to transform Avro payload into Spark's Catalyst one
   *
   * @param rootAvroType Avro [[Schema]] to be transformed from
   * @param rootCatalystType Catalyst [[StructType]] to be transformed into
   * @return converter accepting Avro payload and transforming it into a Catalyst one (in the form of [[InternalRow]])
   */
  def createAvroToInternalRowConverter(rootAvroType: Schema, rootCatalystType: StructType): GenericRecord => Option[InternalRow] = {
    val deserializer = sparkAdapter.createAvroDeserializer(rootAvroType, rootCatalystType)
    record => deserializer
      .deserialize(record)
      .map(_.asInstanceOf[InternalRow])
  }

  /**
   * Creates converter to transform Catalyst payload into Avro one
   *
   * @param rootCatalystType Catalyst [[StructType]] to be transformed from
   * @param rootAvroType Avro [[Schema]] to be transformed into
   * @param nullable whether Avro record is nullable
   * @return converter accepting Catalyst payload (in the form of [[InternalRow]]) and transforming it into an Avro one
   */
  def createInternalRowToAvroConverter(rootCatalystType: StructType, rootAvroType: Schema, nullable: Boolean): InternalRow => GenericRecord = {
    val serializer = sparkAdapter.createAvroSerializer(rootCatalystType, rootAvroType, nullable)
    row => serializer
      .serialize(row)
      .asInstanceOf[GenericRecord]
  }

  /**
   * @deprecated please use [[AvroConversionUtils.createAvroToInternalRowConverter]]
   */
  @Deprecated
  def createConverterToRow(sourceAvroSchema: Schema,
                           targetSqlType: StructType): GenericRecord => Row = {
    val serde = sparkAdapter.createSparkRowSerDe(targetSqlType)
    val converter = AvroConversionUtils.createAvroToInternalRowConverter(sourceAvroSchema, targetSqlType)

    avro => converter.apply(avro).map(serde.deserializeRow).get
  }

  /**
   * @deprecated please use [[AvroConversionUtils.createInternalRowToAvroConverter]]
   */
  @Deprecated
  def createConverterToAvro(sourceSqlType: StructType,
                            structName: String,
                            recordNamespace: String): Row => GenericRecord = {
    val serde = sparkAdapter.createSparkRowSerDe(sourceSqlType)
    val avroSchema = AvroConversionUtils.convertStructTypeToAvroSchema(sourceSqlType, structName, recordNamespace)
    val nullable = AvroSchemaUtils.resolveNullableSchema(avroSchema) != avroSchema

    val converter = AvroConversionUtils.createInternalRowToAvroConverter(sourceSqlType, avroSchema, nullable)

    row => converter.apply(serde.serializeRow(row))
  }

  /**
   * Creates [[org.apache.spark.sql.DataFrame]] from the provided [[RDD]] of [[GenericRecord]]s
   *
   * TODO convert directly from GenericRecord into InternalRow instead
   */
  def createDataFrame(rdd: RDD[GenericRecord], schemaStr: String, ss: SparkSession): Dataset[Row] = {
    if (rdd.isEmpty()) {
      ss.emptyDataFrame
    } else {
      ss.createDataFrame(rdd.mapPartitions { records =>
        if (records.isEmpty) Iterator.empty
        else {
          val schema = new Schema.Parser().parse(schemaStr)
          val dataType = convertAvroSchemaToStructType(schema)
          val converter = createConverterToRow(schema, dataType)
          records.map { r => converter(r) }
        }
      }, convertAvroSchemaToStructType(new Schema.Parser().parse(schemaStr)))
    }
  }

  /**
   * Converts [[StructType]] into Avro's [[Schema]]
   *
   * @param structType    Catalyst's [[StructType]]
   * @param qualifiedName Avro's schema qualified name
   * @return Avro schema corresponding to given struct type.
   */
  def convertStructTypeToAvroSchema(structType: DataType,
                                    qualifiedName: String): Schema = {
    val (namespace, name) = {
      val parts = qualifiedName.split('.')
      (parts.init.mkString("."), parts.last)
    }
    convertStructTypeToAvroSchema(structType, name, namespace)
  }


  /**
   * Converts [[StructType]] into Avro's [[Schema]]
   *
   * @param structType      Catalyst's [[StructType]]
   * @param structName      Avro record name
   * @param recordNamespace Avro record namespace
   * @return Avro schema corresponding to given struct type.
   */
  def convertStructTypeToAvroSchema(structType: DataType,
                                    structName: String,
                                    recordNamespace: String): Schema = {
    try {
      val schemaConverters = sparkAdapter.getAvroSchemaConverters
      val avroSchema = schemaConverters.toAvroType(structType, nullable = false, structName, recordNamespace)
      getAvroSchemaWithDefaults(avroSchema, structType)
    } catch {
      case e: Exception => throw new HoodieSchemaException("Failed to convert struct type to avro schema: " + structType, e)
    }
  }

  /**
   * Converts Avro's [[Schema]] to Catalyst's [[StructType]]
   */
  def convertAvroSchemaToStructType(avroSchema: Schema): StructType = {
    try {
      val schemaConverters = sparkAdapter.getAvroSchemaConverters
      schemaConverters.toSqlType(avroSchema) match {
        case (dataType, _) => dataType.asInstanceOf[StructType]
      }
    } catch {
      case e: Exception => throw new HoodieSchemaException("Failed to convert avro schema to struct type: " + avroSchema, e)
    }
  }

  /**
   *
   * Method to add default value of null to nullable fields in given avro schema
   *
   * @param schema input avro schema
   * @return Avro schema with null default set to nullable fields
   */
  def getAvroSchemaWithDefaults(schema: Schema, dataType: DataType): Schema = {

    schema.getType match {
      case Schema.Type.RECORD => {
        val structType = dataType.asInstanceOf[StructType]
        val structFields = structType.fields
        val modifiedFields = schema.getFields.map(field => {
          val i: Int = structType.fieldIndex(field.name())
          val comment: String = if (structFields(i).metadata.contains("comment")) {
            structFields(i).metadata.getString("comment")
          } else {
            field.doc()
          }
          val newSchema = getAvroSchemaWithDefaults(field.schema(), structFields(i).dataType)
          field.schema().getType match {
            case Schema.Type.UNION => {
              val innerFields = newSchema.getTypes
              val containsNullSchema = innerFields.foldLeft(false)((nullFieldEncountered, schema) => nullFieldEncountered | schema.getType == Schema.Type.NULL)
              if(containsNullSchema) {
                // Need to re shuffle the fields in list because to set null as default, null schema must be head in union schema
                val restructuredNewSchema = Schema.createUnion(List(Schema.create(Schema.Type.NULL)) ++ innerFields.filter(innerSchema => !(innerSchema.getType == Schema.Type.NULL)))
                new Schema.Field(field.name(), restructuredNewSchema, comment, JsonProperties.NULL_VALUE)
              } else {
                new Schema.Field(field.name(), newSchema, comment, field.defaultVal())
              }
            }
            case _ => new Schema.Field(field.name(), newSchema, comment, field.defaultVal())
          }
        }).toList
        Schema.createRecord(schema.getName, schema.getDoc, schema.getNamespace, schema.isError, modifiedFields)
      }

      case Schema.Type.UNION => {
        Schema.createUnion(schema.getTypes.map(innerSchema => getAvroSchemaWithDefaults(innerSchema, dataType)))
      }

      case Schema.Type.MAP => {
        Schema.createMap(getAvroSchemaWithDefaults(schema.getValueType, dataType.asInstanceOf[MapType].valueType))
      }

      case Schema.Type.ARRAY => {
        Schema.createArray(getAvroSchemaWithDefaults(schema.getElementType, dataType.asInstanceOf[ArrayType].elementType))
      }

      case _ => schema
    }
  }

  /**
   * Please use [[AvroSchemaUtils.getAvroRecordQualifiedName(String)]]
   */
  @Deprecated
  def getAvroRecordNameAndNamespace(tableName: String): (String, String) = {
    val qualifiedName = AvroSchemaUtils.getAvroRecordQualifiedName(tableName)
    val nameParts = qualifiedName.split('.')
    (nameParts.last, nameParts.init.mkString("."))
  }
}
