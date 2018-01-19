# Installation


## MacOS X

Due to system updates on Mac with El Capitan, setting up Molly involves slightly more steps than usual. These are as below:

1. Install `sbt` and `graphviz`.

2. If installed, uninstall `apr` and `apr-util`.

3. Download `apr` and `apr-util` from source and install as per instructions.

4. Run `make` in the highest level directory of the cloned repository.

5. `c4` and `z3` libraries will build now.

6. Since environment variables are disabled for system security reasons, create symlinks in the working directory, like so:
```
ln -s ./lib/c4/build/src/libc4/libc4.dylib ./libc4.dylib
ln -s ./lib/z3/build/z3-dist/lib/libz3.dylib ./libz3.dylib
```

7. We are now good to go!


## Arch Linux

1. Make sure, you have `java-8-openjdk`, `sbt`, `graphviz` installed.

2. If not yet done, set the appropriate Java version on your machine:
```
$ sudo archlinux-java set java-8-openjdk
```

3. Clone the repository and `make` everything:
```
$ git clone git@github.com:palvaro/molly.git
$ cd molly
$ make
```
This will take some time.

4. Publish environment variable:
```
$ export LD_LIBRARY_PATH=lib/c4/build/src/libc4/:lib/z3/build/z3-dist/lib/
```

5. Ready to run the examples!
