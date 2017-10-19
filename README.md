# data-model
### Versioning
For now for master branch it's 0.{x}.{y}, where
* x - major versions correlated with release deployments to production
* y - minor increments differentiating code changes that can influence depending projects, iterating from 0 to any 
multi-digit integer numbers with step of 1.

In branches other than master you can choose any scheme to your liking. Version string is stored in `VERSION` file in
project root folder. 

SBT will not overwrite existing artifacts with the same version string when executing `publish` or `publishLocal` unless
the version string ends with "-SNAPSHOT", so this is why the `VERSION` file should always end in this string.  Anyone
can update this string whenever they please as long as they leave "-SNAPSHOT" on the end of it.  To perform an official
release of `data-model` use the `release.sh` script which will remove "-SNAPSHOT", update the version number, and then
`git commit/tag/push` so that there is a commit linking directly to the Artifactory JAR.  `release.sh`
completes by adding "-SNAPSHOT" back to the end of the version string and issuing another `git commit/push`.  The
intent of the two `git push`es is to trigger two separate CircleCI builds.

For non-master branches, the branch name will be prepended to the beginning of the version string (see `build.sbt`) so
that JARs from different branches do not compete.  If you want to find out how your 
artifact from non-master branch will be named so that you can add the particular version as a dependency in other 
projects, then the scheme is as follows: $branch-$version (note that $version probably includes "-SNAPSHOT"). This
string will be present in command line output of the publish command and can be easily looked up there.

All commits to GitHub repo initiate CircleCI builds that culminate (if tests succeed) in an artifact being published to 
a remote repository, that is specified in `build.sbt`.

Also, sbt can be lazy in reimporting artifacts to projects when artifacts in repos are updated without a change to 
version string, so you might not see the changes to the library classes and methods from dependent code when using 
-SNAPSHOT builds. This can be fixed by adding `.changing()` call to dependency definition like so: ```
"com.hamstoo" %% "data-model" % "issue-91-0.9.11-SNAPSHOT" changing()```. With this command this 
dependency will always be reimported on project refresh with `sbt update` command.

### Data migration
Any change to data classes that brings incompatibility of existing MongoDB documents with their data models warrants 
writing some data migration code. Usually this takes the form of DB calls in `Mongo{Datatype}Dao` classes that are 
executed at class init once and consist of a check for older versions in currently connected DB and an optional update 
routine that is supposed to rewrite those documents with newer scheme. These need to be annotated with data-model 
version at which such changes are introduced, need to be blocking so that no data access or writes take place before 
migration, and need to be introduced in the execution order of ascending version.  
TBD: Other approaches to data migration can be chosen, like using MongoDB databases named using version numbers and 
transferring documents between them while updating their scheme, or like storing version numbers inside documents and
letting them coexist in the same collections (this one seems more cumbersome to implement with current tech choices;
debatable). In any case only one approach has to be used.

### To publish to Ivy repo on local machine
* Run `sbt publishLocal`
  * This will allow dependent projects to find `data-model_2.11.jar` and `data-model_2.12.jar` in the local Ivy 
  repository (`~/.ivy2`).

### To build with IntelliJ
1. Click where it says "SBT" (in vertical orientation) all the way on the right of the IntelliJ window.
2. Click the blue, circular Refresh button in the "SBT projects" frame.
    * This will copy files from the `~/.ivy2/cache` to the `target` directory of the project. 
3. Then, and only then, click Build > Build Project.
    * If IntelliJ gives an "unknown artifact" error then make sure you performed the first two steps correctly. All 
    required artifacts/JARs should now be in the `target` directory.

### Connect to Mongo DB with external client
In order to connect to local MongoDB, please, download any client (for example 
[Compass](example https://www.mongodb.com/products/compass)) and set `mongodb://localhost:27017/hamstoo` as URL. There 
is no need to change other settings as for now.

In order to connect to Staging MongoDB Atlas, please see instructions 
[here](https://cloud.mongodb.com/v2/59a86128d383ad301cf45981#clusters/connect?clusterId=mongo-cluster-useast1).
# testkit
### Usage
1. To add core testing functionality like `Spec` and `Matchers`, extends `FlatSpecWithMatchers` trait.
```
// example 
class SomeTest extends FlatSpecWithMathcers {
    "arithmetic" should "work correctly" in {
        1 + 1 shouldEqual 2
        4 / 2 should not equal 3
    }
}
```
2. To handle future value in test, extend `FutureHandler` trait.
```
// example
class SomeTest extends FlatSpecWithMathcers with FutureHandler {
    "arithmetic from future" should "work correctly" in {
        Future.successful(1 + 1).futureValue shouldEqual 2
    }
}
```
3. To set up test environment with embedded actor system, extend `AkkaEnvironment` class
```
class SomeTest extends AkkaEnvironment("actor system name")
```
it will automatically shut down actor system in the end. Look on example:
```
// example

class SomeTest extends AkkaEnvironment("system") with ImplicitSender {

    "An Echo actor" should "send back messages unchanged" in {
        val echo = system.actorOf(TestActors.echoActorProps)
        echo ! "hello world"
        expectMsg("hello world")
     }   
}
```
4. To set up test environment with embedded fake mongodb instance, extend `MongoEnvironment` trait.
```
class SomeTest extends FlatSpecWithMathcers with MongoEnvironment {

    "Testing some mongodb related operation" should "correctly work" in {
        // test
     }   
}
```
it will automatically start and stop mongodb. By default it runs on port 12345 with mongodb version 3.4.1.
You can simply override it if you need:
```
import de.flapdoodle.embed.mongo.distribution.Version

class SomeTest extends FlatSpecWithMathcers with MongoEnvironment {
    override val mongoPort = 27017
    override val mongoVersion = Version.V3_3_1
    "Testing some mongodb related operation" should "correctly work" in {
        // test
     }   
}
```