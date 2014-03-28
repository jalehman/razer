Razer Concepts
==============

The point of this project is to come up with a way to write rich
single-page web applications in a fundamentally different way than
existing approaches. I would like to adopt the most cutting-edge
(within reason) technologies (hence *razer*) possible. Ideas include:

+ [Om](https://github.com/swannodette/om) for building fast, fluid, rich interfaces
+ [Secretary](https://github.com/gf3/secretary) for HTML5 client-side routing
+ [Sente](https://github.com/ptaoussanis/sente) for Websocket support over `core.async` channels
+ [`core.async`](https://github.com/clojure/core.async/) for all asynchronous communication
+ [EDN](https://github.com/edn-format/edn) for data representation and transport
+ [`core.match`](https://github.com/clojure/core.match) for client and server routing
+ Prismatic's [Schema](https://github.com/Prismatic/schema) for data validation/transformation

This is not meant to be a library (yet), but rather a
specification/example of building an application in this
fashion. Please note that this is HIGHLY experimental and a major
work-in-progress. Feel free to use issues for any kind of questions,
comments and ideas.

## Websockets as Transport Mechanism

I would argue that websockets are not just good for "real-time" web
applications, but as a general mode of data transport from client to
server and back (request/response). Traditional HTTP routes can be
replaced with `core.match` and EDN data to a great degree of
efficacy.

## Coordinating Components

The Om (React) component lifecycle is conducive to viewing the
*application itself* as a component. As such, top-level components --
dubbed **coordinating components** -- provide facilities for
initializing app state, managing route (HTML5 History) changes and
corresponding application view change, and other *global concerns*.

## Separation of Client/Server concerns

David Nolen created a reusable Om component called `om-sync` to
synchronize client-side changes in app-state to a server. I would like
to extend this idea to the *entire application*, rather than on a
per-component basis.

### Spec/Goals

There are three cases that need to be handled.

1. **Optimistic Syncing** *(osync)*: Imagine a form that accepts data,
   and on some sort of trigger (*e.g.* "Submit" button)
   creates/updates/deletes some datastore-backed piece of data. What
   we would ideally like to do is reflect the manipulation/creation
   immediately on the client (optimistic), and asynchronously perform
   the server operations required to make this change permanent. In
   the event of failure, the previously made optimistic update would
   be "rolled back".
2. **Data Retrieval** *(fetch)*: As with the HTTP `GET` request, we
   would like to fetch some sort of data from a remote source and then
   load it somewhere into our application. Naturally, we can't
   optimistically load this data because we do not know what it
   is. That means that:
       + We need a facility to indicate to end-users that a request is
         in-progress.
       + We need to know where to put the data once we get ahold of it.
       + Errors/timeouts need to be appropriately handled.
3. **Non-client affecting remote actions** *(remote)*: In some cases
   an action must be taken that performs some remote operation on a
   server where no client feedback is assumed. If there was a
   client-side effect, the operation would fall into one of the above
   cases. In the event of an error however, a facility should be
   provided for a handler.

### Osync

The `:tx-listen` directive of `om.core/root` will intercept all Om
transactions (see `om.core/transact!`) and pass the entire `tx-data`
parameter to the server. Through server-aware design of the
application cursor and use of the `tag` argument to
`om.core/transact!`, the server can be given all of the information
necessary to appropriately perform any datastore-affecting
operations.

<!-- In the event of an error, `tx-data` can be used to roll back the -->
<!-- application state to its previous state. -->

<!-- The `tag` parameter of `transact!` accepts arbitrary data, including -->
<!-- functions or `core.async` channels (TODO: Verify that this is -->
<!-- true). Error/success handlers/channels can be included this way to -->
<!-- implement custom succes-handling/rollback implementations. Let's go -->
<!-- over some use cases for how `tag` could be used to convey neccessary -->
<!-- information to the server. -->

When issuing a `transact!`, a tag can be attached that will specify a
`:route` key and an optional map of `:handlers`. The `:route` key
specifies a map of keywords that identify path the server's handler,
*e.g.* `{:topic :models, :type :review, :action :create}`. The keys
are arbitrary.

Since the point of **osync** is to keep client-side data in sync with
the server, I am not yet sure what an `:on-success` handler would look
like. An `:on-error` handler is much more readily useful, and could be
used to alert the component to received error messages. A sample tag
might look like this:

```clojure
{:route {:topic :models, :type :review, :action :create}
 :handlers {:on-error (fn [tx-data errors]
                          (om/set-state! owner :errors errors)
                          (rollback! tx-data))}}
```

Of course, the `:handlers` key will be `dissoc`ed prior to sending the
tag to the server.

+ TODO: The issue right now is that a transact will provide the path
  to the data that was updated (to the server), but there's no
  context. How to provide context? For instance, I updated the *title*
  field of a *recipe*. I want to update something like `{:title "The
  Title"}`, but the path to the transacted data is just `"The title"`.
