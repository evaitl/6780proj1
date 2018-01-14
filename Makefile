SRCS:=$(wildcard x780/*.java)
CLASSES:= $(SRCS:%.java=%.class)
JARS:=client.jar server.jar

.PHONY: all indent clean

all: myftp myftpserver
	echo "SRCS = $(SRCS)"

indent:
	uncrustify --no-backup -c docs/uncrustify.cfg $(SRCS)

clean:
	-rm $(CLASSES) $(JARS)
	-rm myftp myftpserver

x780/%.class: x780/%.java
	javac $<

client.jar: $(CLASSES) x780/clientmanifest
	jar cfm $@ x780/clientmanifest x780
server.jar: $(CLASSES) x780/servermanifest
	jar cfm $@ x780/servermanifest x780


myftp: client.jar
	echo "#!/bin/sh" > $@
	echo 'java -jar $< $$@' >> $@
	chmod +x $@

myftpserver: server.jar
	echo "#!/bin/sh" > $@
	echo 'java -jar $< $$@' >> $@
	chmod +x $@
