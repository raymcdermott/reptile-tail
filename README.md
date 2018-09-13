# REPtiLe server library

A Clojure server designed to expose a shared PREPL

See the sister project [reptile-tongue](https://github.com/raymcdermott/reptile-tongue) - a browser based client for 
REPtiLe.

## Usage - standalone mode

In this mode, a PREPL socket server will be started as in process.

The command takes these run time parameters:
`http-port` - a number above 1024, on which the web server will be exposed
`shared-secret` - to hand out to clients so that they can connect

```bash
$ clojure -A:reptile 8888 warm-blooded-lizards-rock
```

## Usage - connected mode

In this mode, another process is running a PREPL socket server and REPtiLe will connect to it.

The command takes these run time parameters:
`http-port` - a number above 1024, on which the web server will be exposed
`shared-secret` - to hand out to clients so that they can connect

`socket-host` - the host which is running the socket server
`socket-port` - port number for socket server

```bash
$ clojure -A:reptile 8888 warm-blooded-lizards-rock localhost 9075
```

An example of an application that can be used as the other process in this mode 
is the [add-lib-demo-app](https://github.com/raymcdermott/add-lib-demo-app)

## Plan

The first version will be considered feature complete once the server provides

- [X] Shared REPL state
- [X] Shared view of edits in real-time 
- [X] Shared REPL history
- [X] Authentication using shared secret
- [X] Dynamic addition of new libraries to the REPL
- [ ] Documentation to explain client / server hosting options
- [ ] Limit user count (2, 3, 4, etc...)
- [ ] Limit users based on user names (jane, joe, mary, etc...)
  
## Planned features

After the initial version these additional features are planned

- [ ] Incremental feedback on long running REPL evaluations
- [ ] Cancel long running REPL evaluations
- [ ] Choice of Java / node runtime REPLs
- [ ] Investigate GraalVM

## License

Copyright © 2018 Ray McDermott

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
