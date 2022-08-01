# Cloud-based SonarQube 
>**Pre-requisities:**
* AWS EC2 (at least t2.small)
* JDK 11
* Latest docker , docker-compose
* PostgreSQL 12
* Linux 20.4

I will use docker-compose so I create docker-compose.yml file. You can check it.

> **Requirements for SonarQube:**
>> **Hardware Requirements:**
>>1.  A small-scale (individual or small team) instance of the SonarQube server requires at least 2GB of RAM to run efficiently and 1GB of free RAM for the OS. If you are installing an instance for a large teams or Enterprise, please consider the additional recommendations below.
>>2.  The amount of disk space you need will depend on how much code you analyze with SonarQube.
>>3.  SonarQube must be installed on hard drives that have excellent read & write performance. Most importantly, the "data" folder houses the Elasticsearch indices on which a huge amount of I/O will be done when the server is up and running. Great read & write hard drive performance will therefore have a great impact on the overall SonarQube server performance.
>>4.  SonarQube does not support 32-bit systems on the server side. SonarQube does, however, support 32-bit systems on the scanner side.
>
>>**JDK and Database Requirements:**
>>* The SonarQube server require Java version 11 and the SonarQube scanners require Java version 11 or 17.
>>* PostgreSQL 13,12,11,10 and 9.6 is supported by SonarQube.


## INSTRUCTIONS
I recommended to use ssh for security reason so that I will create ssh key when I create new EC2 instance. Get .pem file while creating the instance and save it in your machine.
> ssh -i <<path to your .pem file>> ec2-user@<< ip address of your EC2 

Before we do anything, we should check the max map count and file-max  if you're using Linux machine.
-   `vm.max_map_count`  is greater than or equal to 524288
-   `fs.file-max`  is greater than or equal to 131072
-   the user running SonarQube can open at least 131072 file descriptors
-   the user running SonarQube can open at least 8192 threads
You can see the values with the following commands:

```
sysctl vm.max_map_count
sysctl fs.file-max
ulimit -n
ulimit -u

```

You can set them dynamically for the current session by running the following commands as  `root`:

```
sysctl -w vm.max_map_count=524288
sysctl -w fs.file-max=131072
ulimit -n 131072
ulimit -u 8192
```
But I want to set this numbers permanently so We must update /etc/sysctl.conf.

![](https://blogger.googleusercontent.com/img/a/AVvXsEi6usx0CVRuiTac0gZp2qUaWijMLzUa3p-qH1TEFzd_tPyqPbju37OeNxm63I-eI177VOI685YBJsfGROWF6sjBCZoqb3kLmxM9QY2RNqPPXHIGI8vIySsaerRBTcAkJXnKyoLAmRyYGKFaBSJLp7LMtXQHJElRb_0dOlv_4v2hthNk08JIc-tzC13o=s312)


After we add it, we make sure changes are getting into effect. Add this commands:
> sudo sysctl -p && sudo apt-get update

 Now, we can install docker-compose to our EC2 machine then We can add current user to docker group
 > sudo apt-get install docker-compose -y \ \\
 > sudo usermode -aG docker $USER

I want to use SonarQube and PostgreSQL in the same machine so We are not  using RDS for database. Create a yaml file and copy-paste my docker-compose file.

```yaml
version: "3.9" # its for latest docker version, you can remove and ignore it.

services:
  sonarqube:
    image: sonarqube:lts-community # I used lts version, you can use newest version.
    depends_on:
      - db
    environment:
      SONAR_JDBC_URL: jdbc:postgresql://db:5432/sonar
      SONAR_JDBC_USERNAME: sonar
      SONAR_JDBC_PASSWORD: sonar
    volumes:
      - sonarqube_data:/opt/sonarqube/data
      - sonarqube_extensions:/opt/sonarqube/extensions
      - sonarqube_logs:/opt/sonarqube/logs
    ports:
      - "9010:9000" # This port is for my local network, its clashed with my portainer port so you can change first port but you shouldn't change second one it has to be 9000
  db:
    image: postgres:12
    environment:
      POSTGRES_USER: sonar
      POSTGRES_PASSWORD: sonar
    volumes:
      - postgresql:/var/lib/postgresql
      - postgresql_data:/var/lib/postgresql/data
#I also add volumes you can modify this section.
volumes:
  sonarqube_data:
  sonarqube_extensions:
  sonarqube_logs:
  postgresql:
  postgresql_data:
```
Now we can start our SonarQube and PostgreSQL with one command.
> sudo docker-compose up
## How to use SonarQube with Jenkins

<p align="center">

[![Jenkins video](https://i.ytimg.com/an_webp/wn9wWYAShag/mqdefault_6s.webp?du=3000&sqp=CNDmmZcG&rs=AOn4CLDYivf2bOhC6cbP-a_2_2iloIlDzw)](https://www.youtube.com/watch?v=Spzk1lrCgNY&t=250s)

</p>

## How to use SonarQube  with Maven
Before we doing anything, We should add some global settings to your maven config. It is located in $MAVEN_HOME/conf or ~/.m2. We will set some plugin prefixes and SonarQube server URL, its default url is localhost:9000 so if you don't user your local computer, we need to change it. Now we installed SonarQube scanner to maven.

```
<settings>
    <pluginGroups>
        <pluginGroup>org.sonarsource.scanner.maven</pluginGroup>
    </pluginGroups>
    <profiles>
        <profile>
            <id>sonar</id>
            <activation>
                <activeByDefault>true</activeByDefault>
            </activation>
            <properties>
                <!-- Optional URL to server. Default value is http://localhost:9000 -->
                <sonar.host.url>
                  http://myserver:9000
                </sonar.host.url>
            </properties>
        </profile>
     </profiles>
</settings>
```
Analyzing a Maven project consists of running a Maven goal:  `sonar:sonar`  from the directory that holds the main project  `pom.xml`. You need to pass an  [authentication token](https://docs.sonarqube.org/latest/user-guide/user-token/)  using the  `sonar.login`  property in your command line.

```
mvn clean verify sonar:sonar -Dsonar.login=myAuthenticationToken

```

In some situations you may want to run the  `sonar:sonar`  goal as a dedicated step. Be sure to use  `install`  as first step for multi-module projects

```
mvn clean install
mvn sonar:sonar -Dsonar.login=myAuthenticationToken

```

To specify the version of sonar-maven-plugin instead of using the latest:

```
mvn org.sonarsource.scanner.maven:sonar-maven-plugin:3.7.0.1746:sonar

```

To get coverage information, you'll need to generate the coverage report before the analysis.

