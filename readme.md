Razer Concepts
==============

## Websockets as Transport Mechanism

I would argue that websockets are not just good for "real-time" web
applications, but as a general mode of data transport from client to
server and back (request/response). Traditional HTTP routes can be
replaced with `core.match` and `EDN` data to a great degree of
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

### Syncing Client Interactions with Server

<!-- By using `:tx-listen` on the root coordinating component's `om/root`, -->
<!-- we can sen -->

There are three cases that need to be handled.

1. **Optimistic Syncing**: Imagine a form that accepts data, and on
   some sort of trigger (*e.g.* "Submit" button)
   creates/updates/deletes some datastore-backed piece of data. What
   we would ideally like to do is reflect the manipulation/creation
   immediately on the client (optimistic), and asynchronously perform
   the server operations required to make this change permanent. In
   the event of failure, the previously made optimistic update would
   be "rolled back".
2. **Data Retrieval**: As with the HTTP `GET` request, we would like
   to fetch some sort of data from a remote source and then load it
   somewhere into our application. Naturally, we can't optimistically
   load this data because we do not know what it is. That means that:
       + We need a facility to indicate to end-users that a request is
         in-progress.
       + We need to know where to put the data once we get ahold of it.
       + Errors/timeouts need to be appropriately handled.
3. **Non-client affecting remote actions**: In some cases an action
   must be taken that performs some remote operation on a server where
   no client feedback is assumed. If there was a client-side effect,
   the operation would fall into one of the above cases. In the event
   of an error however, a facility should be provided for a handler.
