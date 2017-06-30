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