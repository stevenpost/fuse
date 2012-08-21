## Fuse BAI

The **Fuse BAI** (or Business Activity Insight) module is is designed to give insight into the underlying business processes by capturing business events into an audit log that can then be stored somewhere (e.g. a database, NoSQL or hadoop) and then analysed and queried offline without impacting the integration flows.

To use Fuse BAI you define the audit points at which to capture events in your Camel routes either by:

* explicit use of an audit endpoint in routes to specifically route to an audit endpoint, for example using a [wire tap](http://camel.apache.org/wire-tap.html)
* using an **[AuditEventNotifier](https://github.com/fusesource/fuse/blob/master/bai/bai-core/src/main/java/org/fusesource/bai/AuditEventNotifier.java#L71)** to configure rules to define when to capture events in your camel routes without modifying your camel routes directly

We prefer the AuditEventNotifier as it leaves auditing completely separate from your business level integration flows; which should solve the common 80% of audit requirements. If ever you have some really complex requirements feel free to use explicit routing to an audit endpoint using the Camel DSL.

### Specifying generic audit rules via AuditEventNotifier

You can configure an instance of AuditEventNotifier using Java or your dependency injection framework like Spring or CDI. You can disable or filter which events are raised along with filter on which endpoints to audit.

Events are then sent to an *audit endpoint* via the [endpointUri property](https://github.com/fusesource/fuse/blob/master/bai/bai-sample-camel/src/test/resources/org/fusesource/bai/sample/FilterExpressionTest-context.xml#L45).

The AuditEventNotifier is then a bean configured in your application (e.g. in a spring XML like this [example spring XML](https://github.com/fusesource/fuse/blob/master/bai/bai-sample-camel/src/main/resources/META-INF/spring/context.xml#L8)).

The AuditEventNotifier implementation is currently based on the [PublishEventNotifier](http://camel.apache.org/maven/current/camel-core/apidocs/org/apache/camel/management/PublishEventNotifier.html) plugin in Camel which filters events and then writes the AuditEvents to the audit endpoint (which is a regular Camel Endpoint and so can then use the various available [Camel Endpoints](http://camel.apache.org/components.html).

There is nothing to stop you creating multiple AuditEventNotifier instances with different configurations (e.g. to filter different things) and writing to different audit endpoints. Another approach would be to create a single AuditEventNotifier which generates all possible audit events you are interested; then use content based routing on the audit endpoint to write events to different back end components.

### Underlying event types

There are different kind of exchange events raised by Camel

* created: an exchange has been created
* completed: an exchange has been completed (so we capture how long it took to process)
* sending: an endpoint is about to be invoked
* sent: an endpoint has been invoked and for InOut exchange patterns, we have the response
* failure: an exchange failed
* redelivery: we had to redeliver an exchange due retry failures

Each of these kinds of events can be filtered using the include flag on AuditEventNotifier or a Prediate can be specified using a [Camel expression language](http://camel.apache.org/languages.html)

For example see this [sample spring XML](https://github.com/fusesource/fuse/blob/master/bai/bai-sample-camel/src/test/resources/org/fusesource/bai/sample/FilterExpressionTest-context.xml#L27) where the **sentFilter** predicate is set and various events are disabled by setting the related include flag to false.

Also most back ends support the use of an [expression to calculate the payload](https://github.com/fusesource/fuse/blob/master/bai/bai-sample-camel/src/test/resources/org/fusesource/bai/sample/ConfigurableBodyExpressionTest-context.xml#L43) written to the storage system (such as MongoDb).

### Asynchronous delivery of audit events

Typically we expect audit events to be informational and to have minimal impact on the runtime performance of the system. So a common configuration is to send to an endpoint like **vm:audit?waitForTaskToComplete=Never** so that there is minimal impact on the business level routing routes.

Then asynchronously you consume from this endpoint and write them to some back end; or use the MongoDb back end for example.

If you want you could invoke the audit back end directly in your routes without using a vm:audit intermediary; this has the benefit of being transactional and atomic if you are using say, JMS to process messages and the same JMS endpoint as the audit endpoint; at the cost of a little more activity in the business routes. However if you're using ActiveMQ in transactional mode then this will have minimal effect as the send to the audit queue would be mostly asynchronous but would add some latency.

A word of warning on asynchronous delivery; if your JVM terminates you can loose any in process audit messages; if losing an audit message is show stopper you must use a synchronous dispatch; for example send to an audit JMS queue inside the same JMS transaction as your other integration route processing.

### Back ends

We have a MongoDbBackend that can be used to consume the [AuditEvent](https://github.com/fusesource/fuse/blob/master/bai/bai-core/src/main/java/org/fusesource/bai/AuditEvent.java#L30 objects that the AuditEventNotifier emits to store things in a [MongoDb](http://www.mongodb.org/) database.

Back ends are completely optional; you could just use a regular camel route to consume from your *audit endpoint* and use the usual EIPs to content based route them, transform them and write them to some queue / database / noqsl etc.

However the back end implementations try and provide common solutions to auditing such as correlating exchanges based on breadcrumb IDs etc.


### Running the sample

Here is the [sample spring XML](https://github.com/fusesource/fuse/blob/master/bai/bai-sample-camel/src/main/resources/META-INF/spring/context.xml#L8) - we define the **AuditEventNotifier** first; then the MongoDb back end which asynchronously consumes events from the *audit endpoint* and writes them to MongoDb.

Start a local [MongoDb](http://www.mongodb.org/).
Then run the following commands:

    cd bai
    mvn install
    cd bai-sample-camel
    mvn camel:run

A sample camel route should now be running which should have configured the auditing of exchanges to MongoDb.

You can now browse the **bai** database in MongoDb as follows:

### Browsing the events

to use the Mongo shell type:

    use bai
    show collections
    db.baievents.findAll()

Or you could install [mViewer](https://github.com/Imaginea/mViewer) and browse the **bai** database in MongoDb using the web client

### MongoDb collections

* **exchangeXray** contains a list of all the context and route IDs which are beinbg audited; so querying this collection allows tools to render the various event streams
* **baievents** contains all the events in a flat easy to query collection
* **$contextId.$routeId** contains all the exchanges on this route