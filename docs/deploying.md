# Scaling to multiple controllers

Although you can run `decide` on a single computer, many experimenters will eventually want to scale up to multiple devices so they can run several experiments in parallel. `decide` was written with this model in mind. Each controller runs independently, providing a high degree of fault tolerance, but multiple devices can be connected in a private network with a host computer that acts as a gateway. Scaling up is as simple as configuring another device, giving it a unique hostname, and connecting it to your network.

The `decide` software running on each device communicates with `decide-host`, a process running on the host computer, which provides some additional services, including trial and event logging, collating statistics, monitoring connected devices for errors, and providing an overview interface for all the connected devices. If you are concerned about security (which you should be, if you want to access your experiments over a public network), this configuration provides a single access point that can be efficiently secured.

You will need some understanding of Linux and basic networking. For the purposes of this guide, we assume that you've connected all your devices to a private switch, and that the host computer has two Ethernet interfaces. Let's call `eth0` the interface connected to the outside world, and `eth1` the interface connected to the private network, which is on the subnet `192.168.10/24`. All the controllers are also connected to this network through their Ethernet cables (wireless networks are not recommended due to their insecurity and unreliability). No additional configuration of the controllers is needed beyond ensuring that each has a unique hostname and uses DHCP to get an IP address.

These instructions also assume that the host computer is running a modern Linux distribution (they were tested on Debian 8), and that you've installed the `decide-host` source somewhere. You can run the software from the source directory or generate a JAR file that can be deployed independently. Although there are some general security observations in this document, you should consult other sources on securing the host computer, and be sure to keep it updated with critical security patches.

## DHCP

Devices connecting to the private network need to be assigned IP addresses and hostnames. `dnsmasq` is a simple DHCP and DNS server that can accomplish both these tasks for small subnets.  After installing `dnsmasq` on the host, copy `dnsmasq.conf` in this directory to `/etc/dnsmasq.conf` and restart `dnsmasq`. The controllers should now obtain IP addresses on boot in the range `192.168.10.101` to `192.168.10.220`. If you need to connect more than 119 controllers, you know what you're doing already. Confirm that leases are listed on the host computer in `/var/lib/misc/dnsmasq.leases`, and that you can retrieve the IP address of any connected controller as follows: `dig <controller-name> @localhost`.

# Database

`decide-host` currently uses [MongoDB](https://mongodb.org) to store state and log messages. By default, the `decide` database on the host computer is used, but this can be changed by editing `resources/config/decide-config.edn`. The primary mongodb server should run locally on the host, but you may wish to configure the server to replicate its data to another machine. Setting up the database server for mirroring is beyond the scope of this document.

## Firewall

In this configuration, the host computer acts as a secure gateway to the private network. It's strongly recommended to use iptables to restrict access to the host computer on `eth0` (the public interface) to trusted subnets. Add an entry to iptables for the interface of the private network:

```bash
/sbin/iptables -A INPUT -i eth1 -j ACCEPT
```

You will also want to set up forwarding so that devices on the private network to access the Internet (which is useful for distributing software, including upgrades to `decide`). Add these add these entries to your iptables:

```bash
-t nat -A POSTROUTING -o eth0 -j MASQUERADE
-A FORWARD -i eth0 -o eth1 -m state --state RELATED,ESTABLISHED -j ACCEPT
-A FORWARD -i eth1 -o eth0 -j ACCEPT
```

## Mail

Both the controllers and the host use email to notify users of severe errors. These users are specified on the commandline of experiment programs and in the host configuration file. In order for this to work, you need a mail transport agent (MTA) that will accept email from the controllers. You can set up the gateway computer to handle mail delivery.

On Debian, the following steps should work for most configurations.

1. Install `exim4` with `apt-get install exim4`. If you are not asked to configure the server (it may already be installed), run `dpkg-reconfigure exim4-config`. Set the general type of configuration to `internet site`, then configure the server to only accept connections on the private network (`192.168.10.0/24`) and to relay mail from the same. You should also set `exim4` to use a single configuration file.

2. Edit `/etc/exim4/exim4.conf.template` and set `sender_unqualified_hosts` and `recipient_unqualified_hosts` to `MAIN_RELAY_NETS`. Restart the MTA with `systemctl restart exim4.service`.

3. On the controllers, edit `config/host-config.json` on the controllers and set `mail_transport` to `{"host": 192.168.10.1, "port": 25}`. On the host, edit `resources/config/decice-config.edn` and add `:transport {:host "192.168.10.1" :port 25}` under `:email`.

4. To test on the controllers, run `node test/test_mail.js <recipient>` to see if mail gets delivered to a recipient.

With this configuration, emails from the controllers will be addressed from
`<controller-name>@<host-name>`, and emails from the host will be addressed from `decide-host@<host-name>`. You should be able to specify any fully-qualified email as a recipient in configuration files or on the commandline; you can also deliver mail locally to a user on the host machine, in which case users should set up a `.forward` file to make sure these emails reach them at their normal email accounts.

## Host daemon

For debugging and testing purposes, running `lein frodo` in a virtual terminal works well, but for deployment you should compile a jar file and run the program as a daemon so that it's always available.  These instructions assume `systemd` is being used for service management.

1. Run `lein frodo uberjar` in the source directory. Note the name of the jar file this command creates (e.g. `/srv/decide/target/uberjar/decide-2.0.0-standalone.jar`)
2. Edit `docs/decide-host.service` and set the jar path under `ExecStart`.
3. Copy `docs/decide-host.service` to `/etc/systemd/system/`
4. Run `systemctl enable decide-host.service` and then `systemctl start decide-host.service`


### Web proxy

For deployment, `decide-host` should be run behind a dedicated web server. This configuration is necessary to restrict access to authenticated users (and to encrypt the authentication protocol so passwords aren't sent in the clear).  The web server can also act as a proxy for individual controllers running on a private network.

This section details instructions for using [nginx](http://nginx.org/) as the web server. Apache may also work, but does not support proxy operations for websockets as well as nginx at this date.

After installing nginx, copy `decide-server.conf` to `/etc/nginx/sites-available` and create a soft link to the file in `/etc/nginx/sites-enabled`. You will need to generate an SSL/TLS key and certificate and place them in the locations specified in the configuration file. These instructions should work if you have gnutls:

```bash
certtool --generate-privkey --outfile server-key.pem
certtool --generate-self-signed --load-privkey server-key.pem --outfile server-cert.pem
```

Place `server-key.pem` in `/etc/ssl/private`, set ownership to `root.ssl-cert` and permissions to `640`. Place `server-cert.pem` in `/etc/ssl/certs` and set permissions to `644`. Note that browsers should complain about the certificate, because it's not signed by a recognized Certificate Authority. You can add an exception.

You will also need to create the password file `/etc/nginx/passwords`, which is used for authentication. Add users and passwords as needed, or use a more sophisticated authentication system.

Copy the contents of `resources/public` directory to `/srv/www/static` on the host computer. These files will be served by the web server, which will help to reduce the load on the devices.

Start the `decide-host` process. In final deployment, it should run as a daemon and only accept connections from localhost. You will be able to access the host interface at https://hostname/control-panel. Similarly, the controller processes should run as daemons, but they will need to accept connections from external programs. Each controller can be accessed at https://hostname/device/device-name/, where `device-name` is the hostname of the controller.
