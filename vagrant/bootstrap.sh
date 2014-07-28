#!/bin/bash

# Mirror sources, results in faster package downloads
mv sources.list /etc/apt/

# Install Ubuntu packages
apt-get update
apt-get install -y openjdk-7-jdk
apt-get remove -y openjdk-6-jre-headless
apt-get install -y zookeeperd mongodb git

# Make apachectl not require password on sudo
chmod 0440 apachectl-nopasswd
chown root.root apachectl-nopasswd
mv apachectl-nopasswd /etc/sudoers.d/

# Install latest Apache Server 2.4 instead of 2.2
apt-get install -y python-software-properties # apt-add-repository command
apt-add-repository ppa:ondrej/apache2
apt-get update
apt-get install -y apache2

# Configure Apache Server
mv apache2-sling-frontend.conf /etc/apache2/
touch /etc/apache2/mod_proxy_balancer.conf
if [[ $(tail -1 /etc/apache2/apache2.conf) != "Include apache2-sling-frontend.conf" ]]
then
	echo Include apache2-sling-frontend.conf >> /etc/apache2/apache2.conf
fi
ln -sf /etc/apache2/mods-available/headers.load /etc/apache2/mods-enabled/
ln -sf /etc/apache2/mods-available/proxy.load /etc/apache2/mods-enabled/
ln -sf /etc/apache2/mods-available/proxy_balancer.load /etc/apache2/mods-enabled/
ln -sf /etc/apache2/mods-available/slotmem_shm.load /etc/apache2/mods-enabled/
ln -sf /etc/apache2/mods-available/proxy_http.load /etc/apache2/mods-enabled/
ln -sf /etc/apache2/mods-available/lbmethod_byrequests.load /etc/apache2/mods-enabled/

# Configure MongoDB (because default config "bind"s to localhost, thus rejecting all connections from other machines
mv mongodb.conf /etc/
service mongodb restart

# Clean ZooKeeper
rm -rf /var/lib/zookeeper/version-2/*
service zookeeper restart

# Drop oak database from MongoDB
mongo oakblahblah --eval "db.dropDatabase()"

# Configure Git repo
rm -rf testrepo
git init testrepo
mv sling-minion.crank.txt testrepo/
cd testrepo
git add sling-minion.crank.txt
cd ..

# Delete Orchestrator's SLING_HOME
rm -rf sling-orch-crankstart/
