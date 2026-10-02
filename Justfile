#!/usr/bin/env just --justfile

# maven build without tests
build:
   mvn -DskipTests clean package

test:
   mvn test

# start spring boot in docker
start:
   just build && docker compose up -d --build

stop:
   docker compose down

restart:
    just stop && just start

# run locally without docker (servers/ and backups/ in the working directory)
run:
   mvn -DskipTests package && java -jar target/simplycraft-*.jar

# dump compile dependencies to dependencies.txt
dependencies:
  mvn dependency:tree -Dscope=compile > dependencies.txt

# dump dependencies updates to updates.txt
updates:
  mvn versions:display-dependency-updates > updates.txt
