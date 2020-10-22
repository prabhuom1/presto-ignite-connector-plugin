# presto-ignite-connector-plugin
1. Build the jar
   mvn clean install

2. Jar ignite-presto-connector-331-0.0.1-SNAPSHOT-jar-with-dependencies.jar will get generated in target folder.

3. create a  folder  <presto-server>/plugin/ignite
4.copy all the jar from <presto-server>/plugin/mysql to <presto-server>/plugin/ignite
  
5. Delete the presto-mysql-331-services.jar from <presto-server>/plugin/ignite
6. Copy the newly build jar ignite-presto-connector-331-0.0.1-SNAPSHOT-jar-with-dependencies.jar to <presto-server>/plugin/ignite
7.create a presto catalog file  <presto-server>/etc/catalog/ignite.properties and add the below content.change the ip and password accordingly
      connector.name=ignite
      connection-url=jdbc:ignite:thin://localhost:10800
      ignite.user=ignite
      ignite.password=ignite
  
8. restart the presto server.(make sure above change should be on all the node)

Note:- This connector build aginst prestosql version 331 and ignite jdbc driver 2.7.0
It supports below functaionality
1.Select,insert,alter(add and drop column)

  
  
