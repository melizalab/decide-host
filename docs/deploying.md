# Deploying multiple devices

Many experimenters will eventually want to scale up to multiple devices so they can run several experiments in parallel. `decide` was written with this model in mind. Each BBB runs independently, providing a high degree of fault tolerance, but multiple devices can all be connected in a private network with a host computer that acts as a gateway. Scaling up is as simple as configuring another device, giving it a unique hostname, and connecting it to your network.

The `decide` software running on each device communicates with `decide-host`, a process running on the host computer, which provides some additional services, including trial and event logging, collating statistics, monitoring connected devices for errors, and providing an overview interface for all the connected devices. If you are concerned about security (which you should be, if you want to access your experiments over a public network), this configuration provides a single access point that can be more efficiently secured than a host of individual Beaglebones.

We assume that you've connected all your devices to a private switch, and that your host computer has two Ethernet interfaces. For the purposes of this document, `eth0` on the host is connected to the outside world, and `eth1` is connected to the private network, which is on the subnet `192.168.10/24`. All the BBBs are also connected to this network through their Ethernet cables (wireless networks are not recommended due to their insecurity and unreliability). No additional configuration of the BBBs is needed for this configuration, beyond ensuring that each device has a unique hostname.

These instructions assume that the host computer is running a modern Linux distribution (they were tested on Debian 8), and that `decide` has either been installed as an npm module or that the source has been installed somewhere publically accessible.  Although there are some general security observations in this document, you should consult other sources on securing the host computer, and be sure to keep it updated with critical security patches.

## Firewall

Use iptables to restrict access to the host computer on `eth0` (the public interface) to trusted subnets. Add an entry to iptables for the interface of the private network:

```bash
/sbin/iptables -A INPUT -i eth1 -j ACCEPT
```

In order for devices on the private network to access the Internet (which is useful for distributing software, including upgrades to `decide`), you also need to add these entries:

```bash
-t nat -A POSTROUTING -o eth0 -j MASQUERADE
-A FORWARD -i eth0 -o eth1 -m state --state RELATED,ESTABLISHED -j ACCEPT
-A FORWARD -i eth1 -o eth0 -j ACCEPT
```

## DHCP

Devices connecting to the private network need to be assigned IP addresses and hostnames. `dnsmasq` is a simple DHCP and DNS server that can accomplish both these tasks for small subnets.  After installing `dnsmasq` on the host, copy `dnsmasq.conf` in this directory to `/etc/dnsmasq.conf` and restart `dnsmasq`. The BBBs should now be able to obtain IP addresses in the range `192.168.10.101` to `192.168.10.220`. Confirm that leases are listed on the host computer in `/var/lib/misc/dnsmasq.leases`, and that you can retrieve the IP address of any connected BBB as follows: `dig <bbb-name> @localhost`.

## Mail

Both the controllers and the host use email to notify users of severe errors. These users are specified on the commandline of experiment programs and in the host configuration file. In order for this to work, you need a mail transport agent (MTA) that will accept email from the controllers. You can set up the gateway computer to handle mail delivery.

On Debian, the following steps should work for most configurations.

1. Install `exim4` with `apt-get install exim4`. If you are not asked to configure the server (it may already be installed), run `dpkg-reconfigure exim4-config`. Set the general type of configuration to `internet site`, then configure the server to only accept connections on the private network (`192.168.10.0/24`) and to relay mail from the same. You should also set `exim4` to use a single configuration file.

2. Edit `/etc/exim4/exim4.conf.template` and set `sender_unqualified_hosts` and `recipient_unqualified_hosts` to `MAIN_RELAY_NETS`. Restart the MTA with `systemctl restart exim4.service`.

3. Edit `config/host-config.json` on the controllers and set `mail_transport` to `{"host": 192.168.10.1, "port": 25}`. Do the same for the host.

4. To test on the controllers, run `node test/test_mail.js <recipient>` to see if mail gets delivered to a recipient.

With this configuration, emails from the controllers will be addressed from
`<controller-name>@<host-name>`, and emails from the host will be addressed from `decide-host@<host-name>`. You should be able to specify any fully-qualified email as a recipient in configuration files or on the commandline; you can also deliver mail locally to a user on the host machine, in which case users should set up a `.forward` file to make sure these emails reach them at their normal email accounts.

## Host daemon

The program that handles communication with devices on the network is called `decide-host.js` and runs in `node`, although it's written

For debugging and testing purposes the script can be run directly, but for deployment it should be run as a daemon so that it's always available.  These instructions assume `systemd` is being used for service management.

First, make sure `node.js` and `npm` are installed. If installing from source, run `npm install` in the source directory to install dependencies. You will also need to compile the Clojurescript code to Javascript by running `lein cljsbuild once` (you can get Leiningen [here](http://leiningen.org). Copy `docs/decide-host.service` to `/etc/systemd/system/`

### Web proxy

For deployment, the node.js process on the host needs to run behind a dedicated web server. This configuration is necessary to restrict access to authenticated users (and to encrypt the authentication protocol so passwords aren't sent in the clear).  The web server also acts as a proxy for individual BBBs running on a private network.

This section details instructions for using [nginx](http://nginx.org/) as the web server. Apache may also work, but does not support proxy operations for websockets as well as nginx at this date.

After installing nginx, copy `decide-server.conf` to `/etc/nginx/sites-available` and create a soft link to the file in `/etc/nginx/sites-enabled`. You will need to generate an SSL/TLS key and certificate and place them in the locations specified in the configuration file. These instructions should work if you have gnutls:

```bash
certtool --generate-privkey --outfile server-key.pem
certtool --generate-self-signed --load-privkey server-key.pem --outfile server-cert.pem
```

Place `server-key.pem` in `/etc/ssl/private`, set ownership to `root.ssl-cert` and permissions to `640`. Place `server-cert.pem` in `/etc/ssl/certs` and set permissions to `644`. Note that browsers should complain about the certificate, because it's not signed by a recognized Certificate Authority. You can add an exception.

You will also need to create the password file `/etc/nginx/passwords`, which is
used for authentication. Add users and passwords as needed, or use a more
sophisticated authentication system.

Copy the `static` directory to `/srv/www` on the host computer. These files will be served by the web server, which will slightly reduce the load on the devices.

Start the host node.js process. In final deployment, it should run as a daemon and only accept connections from localhost. You will be able to access the host interface at https://hostname/controller. Similarly, the BBB node.js processes should run as daemons, but they will need to accept connections from external programs. Each BBB can be accessed at https://hostname/device/device-name/, where `device-name` is the hostname of the BBB.

# Log database

`decide-host` can use mongodb for storing log messages. The primary mongodb server should run locally on the host, but you may wish to configure the server to replicate its data to another machine. Setting up the database server
is beyond the scope of this document. To use mongodb logging, set the "log_db" key in `host-config.json` to the uri of the database.
