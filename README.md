# lein-catapult

A Leiningen plugin to proxy TCP/IP-based nREPL connections to a [Drawbridge][] endpoint.  Especially useful with [vim-fireplace][], which only supports TCP/IP-based nREPL sessions.

[Drawbridge]: https://github.com/cemerick/drawbridge
[vim-fireplace]: https://github.com/tpope/vim-fireplace

## Warning: Alpha Code!

lein-catapult is barely more than a proof of concept.  There are tons of printlns, massive stream-of-consciousness comments, etc. scattered throughout the code.  Also, not everything works yet (see the Open Issues later on).

## Usage

Add `[lein-catapult "0.0.1"]` into your global Leiningen config (`~/.lein/profiles.clj`) like so:

```clojure
{:user {:plugins [[lein-catapult "0.0.1"]]}}
```

Establish a local nREPL proxy to a remote Drawbridge server (replace the URL with your Drawbridge URL):

    $ lein catapult https://myuser:mypass@example.com/repl
    Listening on port  54027 ; connected to Drawbridge.

lein-catapult will output the port number on which it is listening for nREPL connections and, if you are running lein-catapult in a Leiningen project, t will also create various `.nrepl-port` and `repl-port` files in your project directory.  This should allow tools like vim-fireplace to seamlessly detect the proxied nREPL port.

You can also add the lein-catapult URL to your local `profiles.clj` file like so:

```clojure
{:dev {:catapult {:url "https://myuser:mypass@example.com/repl"}}}
```

That will allow you to start lein-catapult without having to specify the URL:

    $ lein catapult
    Listening on port  54054 ; connected to Drawbridge.

## Open Issues (please help!)

1. The vim-fireplace `cpp` command doesn't work; someone in the chain doesn't like the `load-file` nREPL op and returns a `:status [unknown-op error]`.  I am not sure if this the Drawbridge server, nREPL, etc.  This really needs to be fixed in order to make lein-catapult useful.
2. Drawbridge sends a new GET request every time lein-catapult checks the nREPL transport.  This causes lein-catapult to send an HTTP request to your web server every 100ms, which is not only wasteful, but also slow (since you have to wait for the next poll to get results back from your REPL).  This is [Drawbridge issue #10][].

[Drawbridge issue #10]: https://github.com/cemerick/drawbridge/issues/10
