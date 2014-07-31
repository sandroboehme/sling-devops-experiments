Sling Devops Experiments, Volume 5: Automatically Crankstarting Minions
=======================================================================

This is the fifth of a series of experiments about how [Apache Sling](http://sling.apache.org) can be made more devops-friendly.
 
Continuing from the Git-driven crank file monitoring mechanism demonstrated in the previous [vol4](../../tree/vol4) experiment, we configure the Orchestrator to automatically crankstart new Sling instances for each new version of this crank file. This fully automates the update process.

The full test scenario is as follows:

1. The Orchestrator is crankstarted and given the Git repository URL of the Minion crank file.
1. The Orchestrator watches that file in Git for new versions. Once a new version `V` is found (or the initial version, when starting), the Orchestrator crankstarts `n` Sling instances from that version. This crank file has a few variables: Sling HTTP port number, MongoDB URL, ZooKeeper URL, etc. which the Orchestrator may optionally set.
1. The Sling instances start and announce themselves to the Orchestrator when ready.
1. Orchestrator has a target version `V` that it wants to expose via the HTTP front-end. Once `n` Sling instances have announced themselves with that target version, the Orchestrator activates them atomically on the front-end.
1. When old Sling instances are not needed anymore, they are killed.

Implementation
--------------

The difference with the previous prototype is that the Orchestrator now automatically starts Minions, using Crankstart. This mechanism requires that the Orchestrator itself  be crankstarted because that is how the Orchestrator is able to determine the path to the Crankstart Launcher JAR. When a set of Minions is no longer needed, the Orchestrator also automatically stops them.

The start/stop mechanism was implemented using the [Apache Commons Exec](http://commons.apache.org/exec) library.

Running
-------

Before running this prototype, it is necessary to prepare your local Maven repository (using JDK 7 or higher):

1. `mvn clean install` this project.
1. Build the required snapshot bundles:
	1. Checkout [Sling trunk](http://svn.apache.org/repos/asf/sling/trunk/) at revision 1609716.
	1. `mvn clean install` the following paths:
		1. `contrib/crankstart`
		1. `bundles/jcr/contentloader`
		1. `bundles/jcr/jackrabbit-base`
		1. `bundles/jcr/jackrabbit-server`
		1. `bundles/jcr/oak-server`
		1. `bundles/extensions/fsresource`
		1. `bundles/extensions/groovy`
		1. `contrib/launchpad/karaf/org.apache.sling.launchpad.karaf`
1. Navigate to the `sample-bundle` directory and build two versions of the sample bundle:
	1. `mvn -P1 clean install`
	1. `mvn -P2 clean install`

### Environment Setup

It is necessary to set up your environment for running the experiment. The following components are necessary:

* [Apache Server](http://httpd.apache.org/) 2.4
* [MongoDB](https://www.mongodb.org/)
* [ZooKeeper Server](http://zookeeper.apache.org/)
* [Git](http://git-scm.com/)

#### Vagrant

The easiest way to set up the environment is by using [Vagrant](http://www.vagrantup.com/) (note that you also need to have [VirtualBox](https://www.virtualbox.org/) installed).

Vagrant will create a virtual machine with the IP address 10.10.10.10, running Ubuntu with the above components already configured for the experiment. `Vagrantfile` and all other necessary files are in the `vagrant` directory.

Before launching the VM:

1. Change `CRANKSTART_LAUNCHER_PATH` variable on line 121 of `Vagrantfile` to point to the Crankstart Launcher JAR built above.
1. If your local Maven repository is not under `~/.m2/repository`, change the variable `MAVEN_REPO_PATH` on line 122 accordingly.

Then, bring the VM up (from the `vagrant` directory):

```
vagrant up
```

After Vagrant is done, you can `ssh` to the VM:

```
vagrant ssh
```

When the VM is no longer necessary, you can shut it down:

```
vagrant halt
```

or destroy it forever:

```
vagrant destroy
```

In Vagrant terms, the process of configuring a plain VM (installing packages, running scripts, etc.) is called *provisioning*. By default, provisioning is only done the first time you bring up a VM. In case you want to re-provision it (which would bring it to the same state it was when it was first created, with clean MongoDB, ZooKeeper, and the Git repository), you can use the `--provision` flag when bringing it up or

```
vagrant provision
```

if it is already running.

For running the experiment on this VM:

1. `vagrant ssh`
1. `sudo su root`

Other notes:

* Git repository for the Orchestrator is ready at `/home/vagrant/testrepo` and already has an uncommitted `sling-minion.crank.txt` file in it.
* `sling-orch.crank.txt` file is also under `/home/vagrant`.
* Crankstart Launcher JAR is at `/home/vagrant/crankstart-launcher.jar`.
* Balancer configuration file for Apache Server is specified as `/etc/apache2/mod_proxy_balancer.conf`.
* HTTP front-end runs on the default port 80.

### Launching the Orchestrator

Crankstart the Orchestrator:

```
java -Dgit_repo=<path-to-git-repo> -Dhttpd_balancer_config=<path-to-balancer-config-file> -jar <crankstart-launcher>.jar <sling-orch.crank.txt>
```

where

* `<path-to-git-repo>` is the Git repository in which the Orchestrator will monitor `sling-minion.crank.txt`
* `<path-to-balancer-config-file` is the path to the Balancer configuration file as specified in `httpd.conf`
* `<crankstart-launcher.jar>` is the path to the Crankstart Launcher JAR
* `<sling-orch.crank.txt>` is the path to it

i.e. on Vagrant:

```
java -Dgit_repo=/home/vagrant/testrepo -Dhttpd_balancer_config=/etc/apache2/mod_proxy_balancer.conf -jar crankstart-launcher.jar sling-orch.crank.txt
```

Additionally, you may specify additional parameters using the same `-D` switches before the `-jar` switch:

* `httpd`: path to the `httpd` executable (default: `httpd`, i.e. assumed to be on your `PATH`)
* `port`: port on which to start the Orchestrator (default: 1240)
* `n`: number of Minions to start for each config (default: 2)
* `zk_conn_string`: ZooKeeper connection string (default: `localhost:2181`)

Once the Orchestrator loads, you should be able to access its status page at `/orch/status.html`, i.e. <http://10.10.10.10:1240/orch/status.html> on Vagrant.

### Executing the Scenario

Commit the `sling-minion.crank.txt` file to the Git repository. The Orchestrator should detect the commit and start `n` Minions from it. You should be able to monitor the progress from the status page.

Once the Minions are ready and the Orchestrator activates the front-end, you should see something at `/mynode.test` of your HTTP frontend, i.e. <http://10.10.10.10/mynode.test> on Vagrant. The `test` script rendering the node displays three things:

* The content of the node
* The version of the `test` script
* The version of a test OSGi service

where the `test` script and the test OSGi service are supplied by the `org.apache.sling.samples.test` bundle specified in the Minion crank file.

Now modify the version of this bundle from 0.0.1 to 0.0.2 and commit the crank file again. The Orchestrator should detect the change and start `n` more Minions from the new file. Once these new Minions announce their readiness to the Orchestrator, the HTTP front-end should switch to them and the Orchestrator should terminate the old Minions. The updated bundle supplies updated versions of the `test` script and the test OSGi service, and you should see respective changes at `/mynode.test`.

The switch on the front-end is atomic (there is no point in time at which some of the elements rendered on the page come from the new Minion version and some from the old) and zero-downtime.

**Note:** Every time before re-running the experiment, it is necessary to delete the `sling-orch-crankstart` folder created when running the Orchestrator. This folder is used as the Sling home directory of the Orchestrator, and restarting Sling from an existing home directory is not yet supported with Crankstart.

Testing
-------

To verify that the switch between the Sling configs is atomic and consistent (from the client point of view), the `HttpResourceMonitor` tool from the `tools` directory can be used. This tool sends an HTTP request over and over in a single thread and logs changes in responses.

Usage:

```
HttpResourceMonitor address
```

e.g. to monitor the output of the `test` script on the Vagrant VM:

```
java -cp target/org.apache.sling.devops.tools-0.0.1-SNAPSHOT.jar org.apache.sling.devops.tools.HttpResourceMonitor http://10.10.10.10/mynode.test
```
