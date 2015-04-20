# decide-host

Decide is an event-driven state-machine framework for running behavioral experiments on node.js. This repository contains code for `decide-host`, a process that collates data from devices running `decide`.

## Getting started

Detailed instructions for configuring a private local area network for your devices are given in [[docs/deploying]]. For testing purposes, you can run `decide-host` on the computer connected by USB link to the device, or even on the same computer. You'll need to edit `config/host-config.json` and set the following fields:

- `addr_int`: the address of the host computer on the internal network
- `log_db`: the URI of the [mongo](https://mongodb.org) database to store event and trial records. You may omit this field; records are always logged to text files.
- `mail_transport`: specifies the host and port of a mail transport agent that can deliver error messages to users. You may omit this field, in which case mail delivery falls back on the default mechanism used by [nodemailer](http://www.nodemailer.com/). Be warned that this mechanism may fail, so be sure it works before deploying.

`decide-host` is written in [Clojurescript](https://github.com/clojure/clojurescript), which gets compiled to Javascript that runs in [node](https://nodejs.org/). You will need to have [leiningen](http://leiningen.org/) and [npm](https://www.npmjs.com/) installed in order to fetch dependencies and compile the code. Run `lein cljsbuild once` from the root of the repository and then `npm install`.
