# akka-persistence-dynamodb

[![CircleCI](https://circleci.com/gh/j5ik2o/akka-persistence-dynamodb/tree/master.svg?style=shield&circle-token=9f6f53d09f0fb87ee8ea81246e69683d668291cd)](https://circleci.com/gh/j5ik2o/akka-persistence-dynamodb/tree/master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/akka-persistence-dynamodb_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/akka-persistence-dynamodb_2.12)
[![Scaladoc](http://javadoc-badge.appspot.com/com.github.j5ik2o/akka-persistence-dynamodb_2.12.svg?label=scaladoc)](http://javadoc-badge.appspot.com/com.github.j5ik2o/akka-persistence-dynamodb_2.12/com/github/j5ik2o/akka/persistence/dynamodb/index.html?javadocio=true)

This plugin is derived from [dnvriend/akka-persistence-jdbc](https://github.com/dnvriend/akka-persistence-jdbc), not [akka/akka-persistence-dynamodb](https://github.com/akka/akka-persistence-dynamodb).

There are the following reasons.

- use non-blocking aws-sdk v2
- provide snapshot plugin

## akka-persistence journal plugin

## akka-persistence snapshot plugin

## akka-persistence query plugin

```hocon
dynamo-db-read-journal {
  class = "com.github.j5ik2o.akka.persistence.dynamodb.query.DynamoDBReadJournalProvider"

  write-plugin = "dynamo-db-journal"

  dynamodb-client {
    access-key-id = "x"
    secret-access-key = "x"
    endpoint = "http://127.0.0.1:8000/"
  }

}
```

```scala
val readJournal : ReadJournal 
  with CurrentPersistenceIdsQuery 
  with PersistenceIdsQuery 
  with CurrentEventsByPersistenceIdQuery 
  with EventsByPersistenceIdQuery 
  with CurrentEventsByTagQuery 
  with EventsByTagQuery = PersistenceQuery(system).readJournalFor(DynamoDBReadJournal.Identifier)
```

## License

Apache License

This product was made by duplicating or referring to the code of the following products, so Dennis Vriend's license is included in the product code and test code.

- [dnvriend/akka-persistence-jdbc](https://github.com/dnvriend/akka-persistence-jdbc)
- [dnvriend/akka-persistence-query-test](https://github.com/dnvriend/akka-persistence-query-test)


