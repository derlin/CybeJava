# CybeJava

A command-line tool inspired from git to sync content between a Moodle/Cyberlearn course page and a local folder.

> It is currently tested on https://cyberlearn.hes-so.ch, which is a Moodle 2 platform (the only one I have access to). For Moodle 1, have a look at [CybeJava-old](https://github.com/derlin/CybeJava-old). If you want support for your platform, contact me, I would be happy to help.

## Structure

This project is split between two maven projects:
- __cybe-lib__ is a library handling moodle/cyberlearn specifics. Classes allows you to easily connect, authenticate, list courses and resources, download files;
- __cybe-cmdline__ is the actual terminal application. It is inspired by git for commands and behavior.

## Build and run

1. Clone the repo
2. Run `mvn package` in the root directory
3. Launch the cmdline jar, using `ava -jar cybe-cmdline/target/cybe-cmdline-<version>-full.jar`

# Command Line App
 
The command-line app includes a basic interpreter and also supports commands passed as program argument.

__configuration__

First of all, you need to enter your moodle/cyberlearn credentials and select your platform:

    cybe init-global
  
This will create a file `.cybeconf` in your home directory with the following structure:
```json
{
  "username": "lucy.linder",
  "password": "<encrypted pass>",
  "target platform": "cyberlearn.hes-so"
}
```

__initialise a directory and pull resources__ 

The system is ready to use.
Navigate to the directory you want to sync to and use the `init` command to tell cybe which course you want this folder to be bound to. This will create a `.cybe` inside the directory with some metadata. If you won't use cybe anymore, just delete the file.

Once a directory is initialised (i.e. contains a valid `.cybe` file), you can use `pull` to download all the resources (skipping the ones you already have) from the course page.

Here is an example:

    java -jar cybe.jar
    > init
    Select a course 
      [0] C-PrivLaw-A - Privacy  and Law 
      [1] GPU - Programmation parall&egrave;le sur GPU
      [2] T-Alg - Algorithmique
      [3] T-MachLe - Machine Learning
      [4] VI - Visualisation de l&#039;Information

    Your choice [0-4] 4
    Saving LocalConfig...
    > pull
      --> SAVING InfoVis09-2c.pdf (thread: 13)
      ...
      --> SAVING InfoVisMSE_01.pdf (thread: 12)
      --> SAVING UV_Intervention_He-Arc_v20161120.pdf (thread: 17)
    > exit
    Saving LocalConfig...
    Cleaning up.
    Done.

whenever you want, rerun `pull` to check for new resources. 

## Other

__renaming files__: you can rename files downloaded by cybe. Out-of-the box, cybe will detect the change and won't re-download the renamed resources again. If you want to move a resource in another directory, just tell cybe with `add-dir [path]`, so it can detect the resource is already present on the local machine (use `rm-dir` to undo).

__changing password__: simply rerun `init-global` and enter your new credentials.

__downloading other types of resources__: by default, cybe downloads resources of type "pdf", "text/plain", "zip" and "doc". If a course has different resources you want to automatically pull, run `cybe add-ctype [type]`. Note that this must be done on a folder basis.

    > add-ctype tar html
    tar : added.
    html : added.

__manually downloaded resources__: if you already have some files in the directory (that cybe should not re-download/override), you can use the command `resync`: cybe will memorize the unique ids of the files present and ignore them in future `pull`. Note that it won't work if you renamed the files before the sync.

__viewing the current config__: the `dump` command will display the content of the `.cybe` file in the interpreter/terminal. It is the same result if you type `cat .cybe` in a unix-terminal.

## Full list of commands

* __init__: bind the current directory to one of your Cyberlearn course 
* __pinit__:  same as cybe init && cybe pull 
* __init-global__: store your credentials once and for all 
* __add-origin url [urls]__: add an url to the parsing process 
* __rm-origin url [urls]__: remove an url from the parsing process 
* __add-ctype ctype [ctypes]__: add a content-type to the list of valid resources 
* __rm-ctype ctype [ctypes]__: remove a user-defined content-type. Note that this command 
* __add-dir path [paths]__: mark the directory(-ies) as containing downloaded resources 
* __rm-dir path [paths]__: remove the directory(-ies)  
* __pull__: download the latest resources for the course bound  
* __resync__: force a resync of the existing resources list. May be useful when the app bugged. Note that the whole list fileid <--> filename will be reset, so if some files were renamed, you need to manually edit the new .cybe file... 
* __oneshot url__: parse the given url and download resources in the  
* __help__: print a brief help message 
* __man__: display the full documentation 
* __dump__: display the content of the current local configuration 
