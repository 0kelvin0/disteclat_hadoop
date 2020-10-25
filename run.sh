mkdir -p classes
rm -Rf classes/*
javac -classpath $($HADOOP_HOME/bin/hadoop classpath) -d classes ./src/distEclat/*.java
jar -cvf eclat.jar -C classes .