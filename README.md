# re-re-frame
```
                                    ____                        
   ________        ________        / __/________ _____ ___  ___ 
  / ___/ _ \______/ ___/ _ \______/ /_/ ___/ __ `/ __ `__ \/ _ \
 / /  /  __/_____/ /  /  __/_____/ __/ /  / /_/ / / / / / /  __/
/_/   \___/     /_/   \___/     /_/ /_/   \__,_/_/ /_/ /_/\___/ 
```

[![Clojars Project](https://img.shields.io/clojars/v/com.jothedev/re-re-frame.svg)](https://clojars.org/com.jothedev/re-re-frame)

## 🧩 The last missing piece of `re-frame`

- Fixes the problem of calling `subscribe` inside an event handler ([The "Subscribe was called outside of a reactive context error"](https://day8.github.io/re-frame/FAQs/UseASubscriptionInAnEventHandler/))
- Enables dispatching synchronously from an event handler
- Simplifies the creation of handlers and subscriptions
- Adds easy-to-use app-db validation

## TABLE OF CONTENTS

- [re-re-frame](#re-re-frame)
  - [🧩 The last missing piece of `re-frame`](#-the-last-missing-piece-of-re-frame)
  - [TABLE OF CONTENTS](#table-of-contents)
  - [🫳 `reg-grab` (the new `reg-sub`)](#-reg-grab-the-new-reg-sub)
    - [🚦 Signals with `reg-grab`](#-signals-with-reg-grab)
  - [🫳 `grab`](#-grab)
  - [✏️ `reg-event-x` (the new `reg-event-db`/`reg-event-fx`)](#️-reg-event-x-the-new-reg-event-dbreg-event-fx)
    - [Function return](#function-return)
    - [Ambiguity with the function return](#ambiguity-with-the-function-return)
      - [Use namespaced keywords as the identifier to `reg-fx`s.](#use-namespaced-keywords-as-the-identifier-to-reg-fxs)
      - [If you can't use a namespaced keyword as the identifier to `reg-fx`, add it to the "custom fx" list.](#if-you-cant-use-a-namespaced-keyword-as-the-identifier-to-reg-fx-add-it-to-the-custom-fx-list)
      - [If the return value is still being misinterpreted](#if-the-return-value-is-still-being-misinterpreted)
    - [`dispatch-sync` and `dispatch-sync-n`](#dispatch-sync-and-dispatch-sync-n)
  - [🧪 Testing](#-testing)
  - [🔗 Aliases](#-aliases)

## 🫳 `reg-grab` (the new `reg-sub`)

`reg-grab` is the new "replacement" for `reg-sub`. It's syntax is very similar to `reg-sub`:
```clj
(reg-grab
  :example 
  (fn [db arg1 arg2]
    ...))
```

The first arg is the identifing keyword of the subscription, just like `reg-sub`. 

The second arg is a function, whose **args are different from `reg-sub`**. Compare the old function's args:
```clj
(reg-sub
  :example
  (fn [db [_ arg1 arg2]]
    ...))
```
Notice that instead of `arg1` and `arg2` (which get sent with subscribe, like `@(subscribe [:example arg1 arg2])`) being in their own vector, they are on the same level as db:
```clj
(reg-grab
  :example 
  (fn [db arg1 arg2]
    ...))
```
Which means we don't have to ignore that first item in the arg vector anymore. Neat!

### 🚦 [Signals](https://day8.github.io/re-frame/subscriptions/#reg-sub) with `reg-grab`

**It's recommended to just call `grab` inside a `reg-grab` function when you want to use the value of a subscription.** However, you can also use [signals](https://day8.github.io/re-frame/subscriptions/#reg-sub) just like you do with `reg-sub`, with a couple of differences:

1. If you use a signals function, the args sent to it are flattened. No need to destructure an array and skip the first arg. For example, take this old `reg-sub`:
```clj
(reg-sub
 ::person-id-sa?
 (fn [[_ person-key other-arg]] ;; this is the "signals" function
   (subscribe [::person-id-type person-key]))

 (fn [person-id-type [_ person-key other-arg]] ;; this is the "computation" function
   (= person-id-type :sa)))
```
With `reg-grab`, the signals function's args would look like this:
```clj
(reg-grab
 ::person-id-sa?
 (fn [person-key other-arg] ;; signals function
   (subscribe [::person-id-type person-key]))

 (fn [[person-id-type db] person-key other-arg] ;; computation function
   (= person-id-type :sa)))
```
2. If you use [the `:<-` "syntactic sugar"](https://day8.github.io/re-frame/subscriptions/#syntactic-sugar), `grab` will be used to get the subscription's value, so make sure it's registered with `reg-grab`.
3. The results of the signals that you receive in the computation function is **always** a vector, and **always** has the app-db as the last value. For example:
```clj
(reg-grab
 ::signaled-sub

 :<- [::other-sub]
 :<- [::my-other-sub]

 (fn [[other-sub my-other-sub db] arg1 arg2] ;; the db is last item of the first arg vector
   ...))
```
**OR:**
```clj
(reg-grab
 ::signaled-sub

 :<- [::other-sub]

 (fn [[other-sub db] arg1 arg2] ;; even though there's just one signal, the first arg is a vector, with the db as its last item
   ...))
```

## 🫳 `grab`

Besides making it easier to register a sub, `reg-grab` also registers a method with `grab`. With `grab`, you can safely "grab" the value of a subscription inside of an event handler (apparently you're not supposed to call `subscribe` instead an event handler, since it throws a ["Subscribe was called outside of a reactive context error"](https://day8.github.io/re-frame/FAQs/UseASubscriptionInAnEventHandler/) ¯\\\_(ツ)\_/¯).

Using `grab` inside an event handler is easy:

```clj
(reg-event-db
  :example 
  (fn [db]
    (let [my-value (grab db :example)]
      ...)))
```

Just provide the app-db as the 1st arg, and the identifing keyword of the `reg-grab` method as the 2nd arg. (e.g. `@(subscribe [:example])` could be written as `(grab db :example)`.)

If you want to send args to the `reg-grab` method, add them after the identifier. For example, `(grab db :example arg1 arg2)`.

## ✏️ `reg-event-x` (the new `reg-event-db`/`reg-event-fx`)

`reg-event-x` is the new "replacement" for both `reg-event-db` and `reg-event-fx`. It's syntax is very similar to both:
```clj
(reg-event-x
  :example 
  (fn [db arg1 arg2]
    ...))
```

The first arg is the identifing keyword of the handler, just like `reg-event-db` or `reg-event-fx`.

The second arg is a function, whose **args are different then the other handlers.** Compare `reg-event-db`'s args:
```clj
(reg-event-db
  :example 
  (fn [db [_ arg1 arg2]]
    ...))
```
And `reg-event-fx`'s args:
```clj
(reg-event-fx
  :example 
  (fn [{:keys [db]} [_ arg1 arg2]]
    ...))
```
With `reg-event-x`, the first arg is always just the app-db. And just like `reg-grab`, instead of `arg1` and `arg2` (which get sent with subscribe, like `@(subscribe [:example arg1 arg2])`) being in their own vector, they are on the same level as db:
```clj
(reg-event-x
  :example 
  (fn [db arg1 arg2]
    ...))
```
Which means we don't have to ignore that first item in the arg vector anymore. Still neat!

### Function return 

The return of the function can either be like that of `reg-event-db` **OR** `reg-event-fx` (hence the "x" in `reg-event-x`); the handler uses some magic (and a bit of guess work) to determine how the return should be interpreted.

If you want to just update the app-db (like in `reg-event-db`), just return the modified app-db. If you want to dispatch events, fx, and/or update the app-db (like in `reg-event-fx`), return a map with the keys you'd use on `reg-event-fx`: `db`, `dispatch`, `fx`, etc.

For example, this handler would simply update the app-db:
```clj
(reg-event-x
  :example 
  (fn [db arg1]
    (assoc db :some-key arg1)))
```
While this handler would update the app-db **and** dispatch the event `:example-2`:
```clj
(reg-event-x
  :example 
  (fn [db arg1]
    {:db (assoc db :some-key arg1)
     :dispatch [:example-2]}))
```

### Ambiguity with the function return

`reg-event-x` will do its best to guess what to do with your return value. But because the app-db can have literally any key, certain return values can lead to ambiguity. To avoid this, adhere to these guidelines:

#### Use namespaced keywords as the identifier to `reg-fx`s.
If a namespaced keyword is encountered, `reg-event-x` will know it's the name of an fx and not a key inside the app-db, and the handler will be treated like `reg-event-fx`.

#### If you can't use a namespaced keyword as the identifier to `reg-fx`, add it to the "custom fx" list.
[There is a list](./custom_fxs.cljs) of "custom fx" identifiers that signal to `reg-event-x` that the key is the name of an fx and not a key inside the app-db. You can [add to this list](./custom_fxs.cljs) if needed.

#### If the return value is still being misinterpreted
If the return value is still being misinterpreted as the app-db instead of a map of fx, include the db in the return value. (You don't have to modify it.)

### `dispatch-sync` and `dispatch-sync-n`

When a event handler includes a `:dispatch` to dispatch other events, the dispatching happens asynchronously. But what if you want it to happen immediately, as if it was joined with that event? That's what `dispatch-sync` and `dispatch-sync-n` are for.

When registering an event handler, as part of your returned fx map, include the key `:dispatch-sync` with the dispatch map to occur. Or include `:dispatch-sync-n`, with a vector of multiple dispatch maps. As of now, you cannot include `:dispatch-sync` or `:dispatch-sync-n` in the `:fx` vector.

**How it works under the hood:** when you use `:dispatch-sync` or `:dispatch-sync-n`, re-re-frame will call those event handler's functions and merge their resulting fx maps with the current event's fx map. It also sends each previous event handler's resulting app-db, as if each handler was called one-at-a-time in order.

## 🧪 Testing

Testing the re-frame app-db's validity against a spec is super easy. On your app boot, simply call `re-re-frame.testing/init!` with your [`clojure.spec.alpha`](https://clojuredocs.org/clojure.spec.alpha) spec. In debug, this will register a global interceptor that validates the app-db with every handler.

If the app-db is invalid, the error **`[Development] app-db spec check has failed!`** will be printed in the console, with an explanation and what event triggered the failure. After this error's explanation has been printed once, it won't be printed again (instead only the error message "[Development] app-db spec check has failed!" will be printed), so the console doesn't get spammed with a million duplicates of the same error explanation.

Because these validation errors are only thrown in the console log, it's a good idea to keep it open during development ;)

## 🔗 Aliases

`re-re-frame.core` also "aliases" the functions of `re-frame.core` ("alias" meaning it essentially calls it as if you were calling it directly), excluding of course `reg-event-db`, `reg-event-fx`, and `reg-sub`.

These aliases are added so you don't need to require `re-frame` and `re-re-frame` to register subs/events and subscribe/dispatch them - `re-re-frame` provides all the functions you need.