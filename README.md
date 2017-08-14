# data-model
### Versioning
For now for master branch it's 0.{x}.{y}, where
* x - major versions correlated with release deployments to production
* y - minor increments differentiating code changes that can influence depending projects

In branches other than master you can choose any scheme to your liking except the one described above. SBT will not 
overwrite existing artifacts with the same version string when executing `publishLocal` unless the version string ends 
with `-SNAPSHOT`.   
All commits to GitHub initiate CircleCI builds that culminate (if tests successful) in an artifact being published to
 a remote repository, specified in `build.sbt`. Thus care should be taken for commit products to not overwrite 
 each other.
 
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

In order to connect to Staging MongoDB, please, create a new security group and specify your IP in the TCP rule for 
27017 port. Apply new security settings to the ec2 instance and its host name in connection URL. For example, 
`mongodb://ec2-34-204-10-46.compute-1.amazonaws.com/hamstoo`.