#
# Treasure Data:
# This script deploy jar packages with a version number that includes commit-hash of git
# instead of -SNAPSHOT so that we can deploy specific commit to the production system. It
# allows us to deploy hotfixes or improvements before stable releases.
#

EXCLUDE_MODULES = %w|
 plugin/trino-accumulo plugin/trino-accumulo-iterators
 plugin/trino-atop plugin/trino-mongodb
 plugin/trino-cassandra plugin/trino-kafka plugin/trino-testing-kafka plugin/trino-redis docs
 plugin/trino-bigquery plugin/trino-druid plugin/trino-elasticsearch plugin/trino-google-sheets
 plugin/trino-kinesis plugin/trino-kudu plugin/trino-memsql plugin/trino-phoenix
 plugin/trino-pinot plugin/trino-prometheus plugin/trino-raptor-legacy
 plugin/trino-thrift plugin/trino-thrift-api plugin/trino-thrift-testing-server
 plugin/trino-example-http plugin/trino-sqlserver
 plugin/trino-mysql plugin/trino-oracle
 plugin/trino-raptor
 plugin/trino-local-file plugin/trino-ml
 plugin/trino-clickhouse plugin/trino-delta-lake plugin/trino-example-jdbc
 plugin/trino-hudi plugin/trino-ignite
 plugin/trino-mariadb plugin/trino-mysql-event-listener
 plugin/trino-phoenix5 plugin/trino-redshift
 service/trino-verifier service/trino-proxy
 core/trino-server-rpm
 lib/trino-record-decoder
 testing/trino-product-tests testing/trino-product-tests-launcher
 testing/trino-test-jdbc-compatibility-old-driver testing/trino-test-jdbc-compatibility-old-server
 testing/trino-benchmark-querie testing/trino-faulttolerant-tests testing/trino-plugin-reader
 testing/trino-server-dev testing/trino-testing-kafka
 testing/trino-benchto-benchmarks|

def trino_modules
  require "rexml/document"
  pom = REXML::Document.new(File.read("pom.xml"))
  modules = []
  REXML::XPath.each(pom, "/project/modules/module"){|m|
    modules << m.text
  }
  modules
end

def active_modules
  trino_modules.keep_if{|m| !EXCLUDE_MODULES.include?(m) }
end

desc "compile codes"
task "compile" do
  sh "./mvnw -s settings.xml test-compile -pl #{active_modules.join(",")} -DskipTests"
end

desc "run tests"
task "test" do
  sh "./mvnw -s settings.xml -P td -pl #{active_modules.join(",")} test"
end

desc "install to local repository"
task "install" do
  sh "./mvnw -s settings.xml -P td -pl #{active_modules.join(",")} install -DskipTests"
end

desc "exclude unnecessary modules from trino-server provisioning"
task "update-provisio-modules" do
  require "rexml/document"
  provisio = REXML::Document.new(File.read("core/trino-server/src/main/provisio/trino.xml"))
  EXCLUDE_MODULES.each{|m|
    m = m.split('/')[1]
    provisio.delete_element("/runtime/artifactSet[contains(artifact/@id, ':#{m}:zip:')]")
  }
  File.open('core/trino-server/src/main/provisio/trino.xml', 'w'){|f| provisio.write(f) }
end

desc "exclude unnecessary modules from pom.xml"
task "update-pom-modules" do
  require "rexml/document"
  pom = REXML::Document.new(File.read("pom.xml"))
  # delete unnecessary modules from pom.xml
  EXCLUDE_MODULES.each{|m|
     pom.delete_element("/project/modules/module[text()='#{m}']")
  }
  # Dump pom.xml
  File.open('pom.xml', 'w'){|f| pom.write(f) }
end

desc "set a unique version and td-specific settings"
task "update-pom-version" do
  require "rexml/document"

  # Read the current trino version
  rev = `git rev-parse HEAD`
  pom = REXML::Document.new(File.read("pom.xml"))
  trino_version = REXML::XPath.first(pom, "/project/version")

  # Set (trino-version)-(git revision number:first 7 characters) version to pom.xml files
  version = "#{trino_version.text.gsub("-SNAPSHOT", "")}-#{rev[0...7]}"
  sh "./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=#{version} -N"

  # Reload pom.xml
  pom = REXML::Document.new(File.read("pom.xml"))

  # Inject extension plugin to deploy artifacts to s3
  extension = <<EOF
    <extensions>
      <extension>
        <groupId>org.springframework.build</groupId>
        <artifactId>aws-maven</artifactId>
        <version>5.0.0.RELEASE</version>
      </extension>
    </extensions>
EOF
  REXML::XPath.first(pom, "/project/build").add_element(REXML::Document.new(extension))

  # Inject build profile for TD (disable version check, set distribution management tag)
  profiles = REXML::XPath.first(pom, "/project/profiles")
  unless profiles
    profiles = REXML::XPath.first(pom, "/project").add_element("profiles")
  end
  profiles.add_element(REXML::Document.new(File.read("td-profile.xml")))

  distribution_management = <<EOF
    <distributionManagement>
      <repository>
          <id>treasuredata</id>
          <name>treasuredata-releases</name>
          <url>https://treasuredata.jfrog.io/treasuredata/libs-release-local</url>
      </repository>
      <snapshotRepository>
          <id>treasuredata</id>
          <name>treasuredata-snapshots</name>
          <url>https://treasuredata.jfrog.io/treasuredata/libs-snapshot-local</url>
      </snapshotRepository>
    </distributionManagement>
EOF

  releaseRepository = <<EOF
    <repository>
      <id>treasuredata</id>
      <name>treasuredata-releases</name>
      <url>https://treasuredata.jfrog.io/treasuredata/libs-release</url>
      <snapshots>
        <enabled>false</enabled>
      </snapshots>
    </repository>
EOF

  snapshotRepository = <<EOF
    <repository>
      <id>treasuredata-snapshots</id>
      <name>treasuredata-snapshots</name>
      <url>https://treasuredata.jfrog.io/treasuredata/libs-snapshot</url>
      <releases>
        <enabled>false</enabled>
      </releases>
    </repository>
EOF

  repositories = REXML::XPath.first(pom, "/project/repositories")
  unless repositories
    repositories = REXML::XPath.first(pom, "/project").add_element("repositories")
  end
  REXML::XPath.first(pom, "/project/repositories").add(REXML::Document.new(releaseRepository))
  REXML::XPath.first(pom, "/project/repositories").add(REXML::Document.new(snapshotRepository))
  REXML::XPath.first(pom, "/project").insert_after("repositories", REXML::Document.new(distribution_management))
  pom.delete_element("/project/build/plugins/plugin[artifactId/text()='sortpom-maven-plugin']")

  # Dump pom.xml
  File.open('pom.xml', 'w'){|f| pom.write(f) }

end

desc "deploy trino"
task "deploy" do
  # Deploy
  # Deploy trino-root
  sh "./mvnw -s settings.xml deploy -P td -N -DskipTests"
  # Deploy trino modules
  sh "./mvnw -s settings.xml deploy -P td -pl #{active_modules.join(",")} -DskipTests"
end
