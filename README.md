# data-model
### To publish on local machine
* Run `sbt publishLocal`
  * This will allow dependent projects to find `data-model_2.11.jar` in the local Ivy repository (`~/.ivy2`).

### To build with IntelliJ
1. Click where it says "SBT" (in vertical orientation) all the way on the right of the IntelliJ window.
2. Click the blue, circular Refresh button in the "SBT projects" frame.
    * This will copy files from the `~/.ivy2/cache` to the `target` directory of the project. 
3. Then, and only then, click Build > Build Project.
    * If IntelliJ gives an "unknown artifact" error then make sure you performed the first two steps correctly.  All required artifacts/JARs should now be in the `target` directory.

### Connect to Mongo DB with external client
In order to connect to Mongo DB, please, download any client (for example [Compass](example https://www.mongodb.com/products/compass)) and set `mongodb://localhost:27017/hamstoo` as URL. There is no need to change other settings as for now.

In order to connect to Staging Mongo DB, please, create a new security group and specify your IP in the TCP rule for 27017 port. Apply new security settings to the ec2 instance and its host name in connection URL. For example, `mongodb://ec2-34-204-10-46.compute-1.amazonaws.com/hamstoo`.