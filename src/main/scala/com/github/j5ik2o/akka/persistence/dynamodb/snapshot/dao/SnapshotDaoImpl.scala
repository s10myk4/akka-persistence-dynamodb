/*
 * Copyright 2017 Dennis Vriend
 * Copyright 2019 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.j5ik2o.akka.persistence.dynamodb.snapshot.dao
import akka.NotUsed
import akka.persistence.SnapshotMetadata
import akka.serialization.Serialization
import akka.stream.scaladsl.Source
import com.github.j5ik2o.akka.persistence.dynamodb.config.SnapshotPluginConfig
import com.github.j5ik2o.reactive.aws.dynamodb.DynamoDBAsyncClientV2
import com.github.j5ik2o.reactive.aws.dynamodb.akka.DynamoDBStreamClient
import com.github.j5ik2o.reactive.aws.dynamodb.model._

class SnapshotDaoImpl(asyncClient: DynamoDBAsyncClientV2,
                      serialization: Serialization,
                      pluginConfig: SnapshotPluginConfig)
    extends SnapshotDao {
  import pluginConfig._

  private val serializer                         = new ByteArraySnapshotSerializer(serialization)
  private val streamClient: DynamoDBStreamClient = DynamoDBStreamClient(asyncClient)

  def toSnapshotData(row: SnapshotRow): (SnapshotMetadata, Any) =
    serializer.deserialize(row) match {
      case Right(deserialized) => deserialized
      case Left(cause)         => throw cause
    }

  override def delete(persistenceId: String, sequenceNr: Long): Source[Unit, NotUsed] = {
    val req = DeleteItemRequest()
      .withTableName(Some(tableName)).withKey(
        Some(
          Map(
            columnsDefConfig.persistenceIdColumnName -> AttributeValue().withString(Some(persistenceId)),
            columnsDefConfig.sequenceNrColumnName    -> AttributeValue().withNumber(Some(sequenceNr.toString))
          )
        )
      )
    Source.single(req).via(streamClient.deleteItemFlow(parallelism)).map(_ => ())
  }

  private def queryDelete(queryRequest: QueryRequest): Source[Unit, NotUsed] = {
    Source
      .single(queryRequest).via(streamClient.queryFlow(parallelism)).map {
        _.items.getOrElse(Seq.empty)
      }.mapConcat(_.toVector).grouped(batchSize).map { rows =>
        rows.map { row =>
          SnapshotRow(
            persistenceId = row(columnsDefConfig.persistenceIdColumnName).string.get,
            sequenceNumber = row(columnsDefConfig.sequenceNrColumnName).number.get.toLong,
            snapshot = row(columnsDefConfig.snapshotColumnName).binary.get,
            created = row(columnsDefConfig.createdColumnName).number.get.toLong
          )
        }
      }.map { rows =>
        BatchWriteItemRequest().withRequestItems(
          Some(
            Map(
              tableName -> rows.map { row =>
                WriteRequest().withDeleteRequest(
                  Some(
                    DeleteRequest()
                      .withKey(
                        Some(
                          Map(
                            columnsDefConfig.persistenceIdColumnName -> AttributeValue()
                              .withString(Some(row.persistenceId)),
                            columnsDefConfig.sequenceNrColumnName -> AttributeValue()
                              .withNumber(Some(row.sequenceNumber.toString))
                          )
                        )
                      )
                  )
                )
              }
            )
          )
        )
      }.via(streamClient.batchWriteItemFlow(parallelism)).map(_ => ())
  }

  override def deleteAllSnapshots(persistenceId: String): Source[Unit, NotUsed] = {
    val queryRequest = QueryRequest()
      .withTableName(Some(tableName)).withKeyConditionExpression(Some("#pid = :pid and #snr between :min and :max"))
      .withExpressionAttributeNames(
        Some(
          Map("#pid" -> columnsDefConfig.persistenceIdColumnName, "#snr" -> columnsDefConfig.sequenceNrColumnName)
        )
      ).withExpressionAttributeValues(
        Some(
          Map(
            ":pid" -> AttributeValue().withString(Some(persistenceId)),
            ":min" -> AttributeValue().withNumber(Some(0.toString)),
            ":max" -> AttributeValue().withNumber(Some(Long.MaxValue.toString))
          )
        )
      )
    queryDelete(queryRequest)
  }

  override def deleteUpToMaxSequenceNr(persistenceId: String, maxSequenceNr: Long): Source[Unit, NotUsed] = {
    val queryRequest = QueryRequest()
      .withTableName(Some(tableName)).withKeyConditionExpression(Some("#pid = :pid and #snr between :min and :max"))
      .withExpressionAttributeNames(
        Some(
          Map("#pid" -> columnsDefConfig.persistenceIdColumnName, "#snr" -> columnsDefConfig.sequenceNrColumnName)
        )
      ).withExpressionAttributeValues(
        Some(
          Map(
            ":pid" -> AttributeValue().withString(Some(persistenceId)),
            ":min" -> AttributeValue().withNumber(Some(0.toString)),
            ":max" -> AttributeValue().withNumber(Some(maxSequenceNr.toString))
          )
        )
      )
    queryDelete(queryRequest)
  }

  override def deleteUpToMaxTimestamp(persistenceId: String, maxTimestamp: Long): Source[Unit, NotUsed] = {
    val queryRequest = QueryRequest()
      .withTableName(Some(tableName)).withKeyConditionExpression(Some("#pid = :pid and #snr between :min and :max"))
      .withFilterExpression(Some("#created <= :maxTimestamp"))
      .withExpressionAttributeNames(
        Some(
          Map("#pid"     -> columnsDefConfig.persistenceIdColumnName,
              "#snr"     -> columnsDefConfig.sequenceNrColumnName,
              "#created" -> columnsDefConfig.createdColumnName)
        )
      ).withExpressionAttributeValues(
        Some(
          Map(
            ":pid"          -> AttributeValue().withString(Some(persistenceId)),
            ":min"          -> AttributeValue().withNumber(Some(0.toString)),
            ":max"          -> AttributeValue().withNumber(Some(Long.MaxValue.toString)),
            ":maxTimestamp" -> AttributeValue().withNumber(Some(maxTimestamp.toString))
          )
        )
      )
    queryDelete(queryRequest)
  }

  override def deleteUpToMaxSequenceNrAndMaxTimestamp(persistenceId: String,
                                                      maxSequenceNr: Long,
                                                      maxTimestamp: Long): Source[Unit, NotUsed] = {
    val queryRequest = QueryRequest()
      .withTableName(Some(tableName)).withKeyConditionExpression(Some("#pid = :pid and #snr between :min and :max"))
      .withFilterExpression(Some("#created <= :maxTimestamp"))
      .withExpressionAttributeNames(
        Some(
          Map("#pid"     -> columnsDefConfig.persistenceIdColumnName,
              "#snr"     -> columnsDefConfig.sequenceNrColumnName,
              "#created" -> columnsDefConfig.createdColumnName)
        )
      ).withExpressionAttributeValues(
        Some(
          Map(
            ":pid"          -> AttributeValue().withString(Some(persistenceId)),
            ":min"          -> AttributeValue().withNumber(Some(0.toString)),
            ":max"          -> AttributeValue().withNumber(Some(maxSequenceNr.toString)),
            ":maxTimestamp" -> AttributeValue().withNumber(Some(maxTimestamp.toString))
          )
        )
      )
    queryDelete(queryRequest)
  }

  override def latestSnapshot(persistenceId: String): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    val queryRequest = QueryRequest()
      .withTableName(Some(tableName)).withKeyConditionExpression(Some("#pid = :pid and #snr between :min and :max"))
      .withExpressionAttributeNames(
        Some(
          Map("#pid" -> columnsDefConfig.persistenceIdColumnName, "#snr" -> columnsDefConfig.sequenceNrColumnName)
        )
      ).withExpressionAttributeValues(
        Some(
          Map(
            ":pid" -> AttributeValue().withString(Some(persistenceId)),
            ":min" -> AttributeValue().withNumber(Some(0.toString)),
            ":max" -> AttributeValue().withNumber(Some(Long.MaxValue.toString))
          )
        )
      )
      .withScanIndexForward(Some(false))
      .withLimit(Some(1))
    Source
      .single(queryRequest).via(streamClient.queryFlow(parallelism)).map { response =>
        response.items.get.headOption
      }.map { rows =>
        rows.map { row =>
          serializer
            .deserialize(
              SnapshotRow(
                persistenceId = row(columnsDefConfig.persistenceIdColumnName).string.get,
                sequenceNumber = row(columnsDefConfig.sequenceNrColumnName).number.get.toLong,
                snapshot = row(columnsDefConfig.snapshotColumnName).binary.get,
                created = row(columnsDefConfig.createdColumnName).number.get.toLong
              )
            ).right.get
        }
      }
  }

  override def snapshotForMaxTimestamp(persistenceId: String,
                                       maxTimestamp: Long): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    val queryRequest = QueryRequest()
      .withTableName(Some(tableName)).withKeyConditionExpression(Some("#pid = :pid and #snr between :min and :max"))
      .withFilterExpression(Some("#created <= :maxTimestamp"))
      .withExpressionAttributeNames(
        Some(
          Map("#pid"     -> columnsDefConfig.persistenceIdColumnName,
              "#snr"     -> columnsDefConfig.sequenceNrColumnName,
              "#created" -> columnsDefConfig.createdColumnName)
        )
      ).withExpressionAttributeValues(
        Some(
          Map(
            ":pid"          -> AttributeValue().withString(Some(persistenceId)),
            ":min"          -> AttributeValue().withNumber(Some(0.toString)),
            ":max"          -> AttributeValue().withNumber(Some(Long.MaxValue.toString)),
            ":maxTimestamp" -> AttributeValue().withNumber(Some(maxTimestamp.toString))
          )
        )
      ).withScanIndexForward(Some(false))
    Source
      .single(queryRequest).via(streamClient.queryFlow(parallelism)).map { response =>
        response.items.get.headOption
      }.map { rows =>
        rows.map { row =>
          serializer
            .deserialize(
              SnapshotRow(
                persistenceId = row(columnsDefConfig.persistenceIdColumnName).string.get,
                sequenceNumber = row(columnsDefConfig.sequenceNrColumnName).number.get.toLong,
                snapshot = row(columnsDefConfig.snapshotColumnName).binary.get,
                created = row(columnsDefConfig.createdColumnName).number.get.toLong
              )
            ).right.get
        }
      }
  }

  override def snapshotForMaxSequenceNr(persistenceId: String,
                                        maxSequenceNr: Long): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    val queryRequest = QueryRequest()
      .withTableName(Some(tableName)).withKeyConditionExpression(Some("#pid = :pid and #snr between :min and :max"))
      .withExpressionAttributeNames(
        Some(
          Map("#pid" -> columnsDefConfig.persistenceIdColumnName, "#snr" -> columnsDefConfig.sequenceNrColumnName)
        )
      ).withExpressionAttributeValues(
        Some(
          Map(
            ":pid" -> AttributeValue().withString(Some(persistenceId)),
            ":min" -> AttributeValue().withNumber(Some(0.toString)),
            ":max" -> AttributeValue().withNumber(Some(maxSequenceNr.toString))
          )
        )
      ).withScanIndexForward(Some(false))
    Source
      .single(queryRequest).via(streamClient.queryFlow(parallelism)).map { response =>
        response.items.get.headOption
      }.map { rows =>
        rows.map { row =>
          serializer
            .deserialize(
              SnapshotRow(
                persistenceId = row(columnsDefConfig.persistenceIdColumnName).string.get,
                sequenceNumber = row(columnsDefConfig.sequenceNrColumnName).number.get.toLong,
                snapshot = row(columnsDefConfig.snapshotColumnName).binary.get,
                created = row(columnsDefConfig.createdColumnName).number.get.toLong
              )
            ).right.get
        }
      }
  }

  override def snapshotForMaxSequenceNrAndMaxTimestamp(
      persistenceId: String,
      maxSequenceNr: Long,
      maxTimestamp: Long
  ): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    val queryRequest = QueryRequest()
      .withTableName(Some(tableName)).withKeyConditionExpression(Some("#pid = :pid and #snr between :min and :max"))
      .withFilterExpression(Some("#created <= :maxTimestamp"))
      .withExpressionAttributeNames(
        Some(
          Map("#pid"     -> columnsDefConfig.persistenceIdColumnName,
              "#snr"     -> columnsDefConfig.sequenceNrColumnName,
              "#created" -> columnsDefConfig.createdColumnName)
        )
      ).withExpressionAttributeValues(
        Some(
          Map(
            ":pid"          -> AttributeValue().withString(Some(persistenceId)),
            ":min"          -> AttributeValue().withNumber(Some(0.toString)),
            ":max"          -> AttributeValue().withNumber(Some(maxSequenceNr.toString)),
            ":maxTimestamp" -> AttributeValue().withNumber(Some(maxTimestamp.toString))
          )
        )
      ).withScanIndexForward(Some(false))
    Source
      .single(queryRequest).via(streamClient.queryFlow(parallelism)).map { response =>
        response.items.get.headOption
      }.map { rows =>
        rows.map { row =>
          serializer
            .deserialize(
              SnapshotRow(
                persistenceId = row(columnsDefConfig.persistenceIdColumnName).string.get,
                sequenceNumber = row(columnsDefConfig.sequenceNrColumnName).number.get.toLong,
                snapshot = row(columnsDefConfig.snapshotColumnName).binary.get,
                created = row(columnsDefConfig.createdColumnName).number.get.toLong
              )
            ).right.get
        }
      }
  }

  override def save(snapshotMetadata: SnapshotMetadata, snapshot: Any): Source[Unit, NotUsed] = {
    serializer
      .serialize(snapshotMetadata, snapshot) match {
      case Right(snapshotRow) =>
        val req = PutItemRequest()
          .withTableName(Some(tableName))
          .withItem(
            Some(
              Map(
                columnsDefConfig.persistenceIdColumnName -> AttributeValue()
                  .withString(Some(snapshotRow.persistenceId)),
                columnsDefConfig.sequenceNrColumnName -> AttributeValue()
                  .withNumber(Some(snapshotRow.sequenceNumber.toString)),
                columnsDefConfig.snapshotColumnName -> AttributeValue().withBinary(Some(snapshotRow.snapshot)),
                columnsDefConfig.createdColumnName  -> AttributeValue().withNumber(Some(snapshotRow.created.toString))
              )
            )
          )
        Source.single(req).via(streamClient.putItemFlow(parallelism)).map(_ => ())
      case Left(ex) =>
        Source.failed(ex)
    }

  }
}