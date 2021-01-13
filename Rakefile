#
# Treasure Data:
# This script deploy jar packages with a version number that includes commit-hash of git
# instead of -SNAPSHOT so that we can deploy specific commit to the production system. It
# allows us to deploy hotfixes or improvements before stable releases.
#

# EXCLUDE only that circle-ci cannot handle
EXCLUDE_MODULES = %w|
 presto-accumulo
 presto-raptor-legacy
 presto-mysql
 presto-sqlserver
 presto-kudu
 presto-server-rpm
|

EXCLUDE_FROM_COMPILE = %w|presto-docs presto-server-rpm|

def presto_modules
  require "rexml/document"
  pom = REXML::Document.new(File.read("pom.xml"))
  modules = []
  REXML::XPath.each(pom, "/project/modules/module"){|m|
    modules << m.text
  }
  modules
end

def active_modules
  presto_modules.keep_if{|m| !EXCLUDE_MODULES.include?(m) }
end

def compile_target_modules
  presto_modules.keep_if{|m| !EXCLUDE_FROM_COMPILE.include?(m) }
end

desc "compile codes"
task "compile" do
  sh "./mvnw -s settings.xml test-compile -pl #{compile_target_modules.join(",")} -DskipTests"
end

desc "run tests"
task "test" do
  # use full test to verify a cherry-pick is fine
  sh "./mvnw -s settings.xml -P td -pl #{active_modules.join(",")} test"
end

desc "set a unique version and td-specific settings"
task "update-pom" do
  require "rexml/document"

  # Read the current presto version
  rev = `git rev-parse HEAD`
  pom = REXML::Document.new(File.read("pom.xml"))
  presto_version = REXML::XPath.first(pom, "/project/version")

  # Set (presto-version)-(git revision number:first 7 characters) version to pom.xml files
  version = "#{presto_version.text.gsub("-SNAPSHOT", "")}-#{rev[0...7]}"
  sh "./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=#{version} -N"

  # Reload pom.xml
  pom = REXML::Document.new(File.read("pom.xml"))

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

  repositories = <<EOF
    <repositories>
      <repository>
	      <id>treasuredata</id>
	      <name>treasuredata-releases</name>
	      <url>https://treasuredata.jfrog.io/treasuredata/libs-release</url>
	      <snapshots>
          <enabled>false</enabled>
      	</snapshots>
      </repository>
      <repository>
	      <id>treasuredata-snapshots</id>
	      <name>treasuredata-snapshots</name>
	      <url>https://treasuredata.jfrog.io/treasuredata/libs-snapshot</url>
	      <releases>
          <enabled>false</enabled>
	      </releases>
      </repository>
    </repositories>
EOF

  REXML::XPath.first(pom, "/project").add_element(REXML::Document.new(distribution_management))
  REXML::XPath.first(pom, "/project").add_element(REXML::Document.new(repositories))

  # Dump pom.xml
  File.open('pom.xml', 'w'){|f| pom.write(f) }

end

desc "deploy presto"
task "deploy" do
  # Deploy
  # Deploy presto-root
  sh "./mvnw -s settings.xml deploy -P td -N -DskipTests"
  # Deploy presto modules
  sh "./mvnw -s settings.xml deploy -P td -pl #{compile_target_modules.join(",")} -DskipTests"
end
