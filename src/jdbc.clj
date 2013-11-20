;; Copyright 2013 Andrey Antukh <niwi@niwi.be>
;;
;; Licensed under the Apache License, Version 2.0 (the "License")
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns jdbc
  "Alternative implementation of jdbc wrapper for clojure."
  (:import (java.net URI)
           (java.sql BatchUpdateException DriverManager
                     PreparedStatement ResultSet SQLException Statement Types)
           (java.util Hashtable Map Properties)
           (javax.sql DataSource)
           (jdbc.types Connection QueryResult))
  (:require [clojure.string :as str])
  (:refer-clojure :exclude [resultset-seq])
  (:gen-class))

(def ^:dynamic *default-isolation-level* (atom :none))
(def ^:private isolation-level-map {:none nil
                                    :read-commited (java.sql.Connection/TRANSACTION_READ_UNCOMMITTED)
                                    :repeatable-read (java.sql.Connection/TRANSACTION_REPEATABLE_READ)
                                    :serializable (java.sql.Connection/TRANSACTION_SERIALIZABLE)})

(defn set-default-isolation-level!
  "Set a default isolation level for each new
  created connection.

  By default no isolation level is set.

  You can obtain a current default isolation level with:

    (deref *default-isolation-level*)
  "
  [level]
  {:pre [(keyword? level)
         (contains? isolation-level-map level)]}
  (reset! *default-isolation-level* level))

(defn- map->properties
  "Convert some dbspec options to java.utils.Properties instance."
  [m]
  (let [p (Properties.)]
    (doseq [[k v] m] (.setProperty p (name k) (str v)))
    p))

(def ^{:private true :doc "Map of classnames to subprotocols"}
  classnames {"postgresql"     "org.postgresql.Driver"
              "mysql"          "com.mysql.jdbc.Driver"
              "sqlserver"      "com.microsoft.sqlserver.jdbc.SQLServerDriver"
              "jtds:sqlserver" "net.sourceforge.jtds.jdbc.Driver"
              "derby"          "org.apache.derby.jdbc.EmbeddedDriver"
              "hsqldb"         "org.hsqldb.jdbcDriver"
              "h2"             "org.h2.Driver"
              "sqlite"         "org.sqlite.JDBC"})

(defn strip-jdbc-prefix
  "Siple util function that strip a \"jdbc:\" prefix
  from connection string urls."
  [^String url]
  (str/replace-first url #"^jdbc:" ""))

(defn parse-properties-uri [^URI uri]
  "Parses a dbspec as uri into a plain dbspec."
  (let [host      (.getHost uri)
        port      (.getPort uri)
        path      (.getPath uri)
        scheme    (.getScheme uri)
        userinfo  (.getUserInfo uri)]
    (merge
      {:subname (if (pos? port)
                 (str "//" host ":" port path)
                 (str "//" host path))
      :subprotocol scheme
      :classname (get classnames scheme)}
      (when userinfo
        (let [[username password] (str/split userinfo #":")]
          {:user username :password password})))))

(defn- make-raw-connection
  "Given a standard dbspec or dbspec with datasource (with connection pool),
  returns a new connection."
  [{:keys [connection-uri classname subprotocol subname
           datasource username password]
    :as db-spec}]
  (cond
    (string? db-spec)
    (make-raw-connection (URI. (strip-jdbc-prefix db-spec)))

    (instance? URI db-spec)
    (make-raw-connection (parse-properties-uri db-spec))

    connection-uri
    (DriverManager/getConnection connection-uri)

    (and subprotocol subname)
    (let [url (format "jdbc:%s:%s" subprotocol subname)
          etc (dissoc db-spec :classname :subprotocol :subname)
          classname (or classname (classnames subprotocol))]
      (clojure.lang.RT/loadClassForName classname)
      (DriverManager/getConnection url (map->properties etc)))

    (and datasource username password)
    (.getConnection datasource username password)

    datasource
    (.getConnection datasource)

    :else
    (throw (IllegalArgumentException.
             (format "dbspec %s is missing a required parameter" db-spec)))))

(defn- execute-statement
  "Execute a statement and return a result of update counts."
  [stmt]
  {:pre [(instance? Statement stmt)]}
  (seq (.executeBatch stmt)))

(defn make-prepared-statement
  "Given connection and parametrized query as vector with first
  argument as string and other arguments as params, return a
  prepared statement.

  Example:

    (let [stmt (make-prepared-statement conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (println (instance? java.sql.PreparedStatement stmt)))
    ;; -> true
  "
  [conn sqlvec]
  {:pre [(instance? Connection conn)
         (vector? sqlvec)]}
  (let [connection  (:connection conn)
        sql         (first sqlvec)
        params      (rest sqlvec)
        stmt        (.prepareStatement connection sql)]
    (when (seq params)
      (dorun (map-indexed #(.setObject stmt (inc %1) %2) params)))
    stmt))

(defn- wrap-isolation-level
  "Wraps and handles a isolation level for connection."
  [dbspec conn]
  (let [connection  (:connection conn)
        dbspec-il   (:isolation-level dbspec)
        default-il  (deref *default-isolation-level*)]
    (if dbspec-il
      (do
        (when (dbspec-il isolation-level-map)
          (.setTransactionIsolation connection (dbspec-il isolation-level-map)))
        (assoc conn :isolation-level dbspec-il))
      (do
        (when (default-il isolation-level-map)
          (.setTransactionIsolation connection (default-il isolation-level-map)))
        (assoc conn :isolation-level default-il)))))

(defn make-connection
  "Creates a connection to a database. dbspec is a map containing connection
  parameters:

  DriverManager:
    :subprotocol (required) a String, the jdbc subprotocol
    :subname     (required) a String, the jdbc subname
    :classname   (optional) a String, the jdbc driver class name
    (others)     (optional) passed to the driver as properties.

  DataSource:
    :datasource  (required) a javax.sql.DataSource
    :username    (optional) a String
    :password    (optional) a String, required if :username is supplied

  Raw:
    :connection-uri (required) a String
                 Passed directly to DriverManager/getConnection

  URI:
    Parsed JDBC connection string - see below

  String:
    subprotocol://user:password@host:port/subname
                 An optional prefix of jdbc: is allowed."
  [dbspec]
  (let [connection (apply make-raw-connection [dbspec])]
    (wrap-isolation-level dbspec (Connection.
                                    connection       ;; :connection
                                    (atom false)     ;; :in-transaction
                                    (atom false))))) ;; :rollback-only

(defmacro with-connection
  "Given database connection paramers (dbspec), creates
  a context with new connection to database that are closed
  at end of code block.

  If dbspec has datasource (connection pool), instead of create
  a new connection, get it from connection pool and release it
  at the end.

  Example:

    (with-connection dbspec conn
      (do-something-with conn))
  "
  [dbspec bindname & body]
  `(with-open [~bindname (make-connection ~dbspec)]
     ~@body))

(defn mark-as-rollback-only!
  "Mark a current connection with `:rollback-only` flag.

  If a code runs inside a transaction, this ensures that on
  the successful end of execution of your code executes rollback
  instead of commit.

  Example:

    (with-transaction conn
      (make-some-queries-without-changes conn)
      (mark-as-rollback-only! conn))

  "
  [conn]
  {:pre [(instance? Connection conn)]}
  (reset! (:rollback-only conn) true))

(defn unmark-rollback-only!
  "Revert flag setted by `mark-as-rollback-only!`."
  [conn]
  {:pre [(instance? Connection conn)]}
  (reset! (:rollback-only conn) false))

(defn is-rollback-only?
  "Check if a `:rollback-only` flag is set on the
  current connection."
  [conn]
  {:pre [(instance? Connection conn)]}
  (deref (:rollback-only conn)))

(defn call-in-transaction
  "Wrap function in one transaction. If current connection is already in
  transaction, it uses truly nested transactions for properly handle it.
  The availability of this feature depends on database support for it.

  Passed function will reive a connection as first parameter.

  Example:

  (with-connection dbspec conn
    (call-in-transaction conn (fn [conn] (execute! conn 'DROP TABLE foo;'))))

  For more idiomatic code, you should use `with-transaction` macro.
  "
  [conn func & {:keys [savepoints] :or {savepoints true} :as opts}]
  {:pre [(instance? Connection conn)]}
  (when (and @(:in-transaction conn) (not savepoints))
    (throw (RuntimeException. "Savepoints explicitly disabled.")))
  (let [connection      (:connection conn)
        in-transaction  (:in-transaction conn)]
    (if @in-transaction
      (let [savepoint (.setSavepoint connection)]
        (try
          (apply func [conn])
          (.releaseSavepoint connection savepoint)
          (catch Throwable t
            (.rollback connection savepoint)
            (throw t))))
      (let [current-autocommit (.getAutoCommit connection)
            rollback-only      (:rollback-only conn)]
        (swap! in-transaction not)
        (.setAutoCommit connection false)
        (try
          (apply func [conn])
          (if @rollback-only
            (.rollback connection)
            (.commit connection))
          (catch Throwable t
            (.rollback connection)
            (throw t))
          (finally
            (swap! in-transaction not)
            (.setAutoCommit connection current-autocommit)))))))

(defmacro with-transaction
  "Creates a context that evaluates in transaction (or nested transaction).

  This is a more idiomatic way to execute some database operations in
  atomic way.

  Example:

    (with-transaction conn
      (execute! conn 'DROP TABLE foo;')
      (execute! conn 'DROP TABLE bar;'))
  "
  [conn & body]
  `(let [func# (fn [c#] ~@body)]
     (apply call-in-transaction [~conn func#])))

(defn execute!
  "Run arbitrary number of raw sql commands such as: CREATE TABLE,
  DROP TABLE, etc... If your want transactions, you can wrap this
  call in transaction using `with-transaction` context block macro.

  Warning: not all database servers support ddl in transactions.

  Examples:

    ;; Without transactions
    (with-connection dbspec conn
      (execute! conn 'CREATE TABLE foo (id serial, name text);'))

    ;; In one transaction
    (with-connection dbspec conn
      (with-transaction conn
        (execute! conn 'CREATE TABLE foo (id serial, name text);')))
  "
  [conn & commands]
  {:pre [(instance? Connection conn)]}
  (let [connection (:connection conn)]
    (with-open [stmt (.createStatement connection)]
      (dorun (map (fn [command]
                    (.addBatch stmt command)) commands))
      (execute-statement stmt))))

(defn execute-prepared!
  "Same as `execute!` function, but works with prepared statements
  instead with raw sql.

  With this you can execute multiple operations throught
  one call.

  Example:

    (with-connection dbspec conn
      (let [sql 'UPDATE TABLE foo SET x = ? WHERE y = ?;']
        (execute-prepared! conn sql [1 2] [2 3] [3 4])))

    This code should send this sql sentences:

      UPDATE TABLE foo SET x = 1 WHERE y = 2;
      UPDATE TABLE foo SET x = 2 WHERE y = 3;
      UPDATE TABLE foo SET x = 3 WHERE y = 4;
  "
  [conn sql & param-groups]
  {:pre [(instance? Connection conn)]}
  (let [connection (:connection conn)]
    (with-open [stmt (.prepareStatement connection sql)]
      (doseq [param-group param-groups]
        (dorun (map-indexed #(.setObject stmt (inc %1) %2) param-group))
        (.addBatch stmt))
      (execute-statement stmt))))

(defn result-set-lazyseq
  "Function that wraps result in a lazy seq. This function
  is part of public api but can not be used directly (you should pass
  this function as parameter to `query` function).

  Required parameters:
    rs: ResultSet instance.

  Optional named parameters:
    :identifiers -> function that is applied for column name
                    when as-arrays? is false
    :as-arrays?  -> by default this function return a lazy seq of
                    records as map, but in certain circumstances you
                    need results as array. With this keywork parameter
                    you can set result as array instead map record.
  "
  [rs & {:keys [identifiers as-arrays?]
         :or {identifiers str/lower-case as-arrays? false}}]

  (let [metadata    (.getMetaData rs)
        idseq       (range 1 (inc (.getColumnCount metadata)))
        keyseq      (->> idseq
                         (map (fn [i] (.getColumnLabel metadata i)))
                         (map (comp keyword identifiers)))
        values      (fn [] (map #(.getObject rs %) idseq))
        records     (fn thisfn []
                      (when (.next rs)
                        (cons (zipmap keyseq (values)) (lazy-seq (thisfn)))))
        rows        (fn thisfn []
                      (when (.next rs)
                        (cons (vec (values)) (lazy-seq (thisfn)))))]
    (if as-arrays? (rows) (records))))

(defn result-set-vec
  "Function that evaluates a result into one clojure persistent
  vector. Accept same parameters as `result-set-lazyseq`."
  [& args]
  (vec (doall (apply result-set-lazyseq args))))

(defn make-query
  "Given a connection and paramatrized sql, execute a query and
  return a instance of QueryResult that works as stantard clojure
  map but implements a closable interface.

  This functions indents be a low level access for making queries
  and it delegate to a user the resource management. You should
  use `with-open` macro for store a result as example:

    (with-open [result (make-query conn [\"SELECT foo FROM bar WHERE id = ?\" 1])]
      (doseq [row (:data result)]
        (println row)))

  A QueryResult contains a these keys:

  - `:stmt` as PreparedStatement instance
  - `:rs` as ResultSet instance
  - `:data` as lazy seq of results.

  You can pass options on call `make-query` for make `:data` key as
  evaluated (not lazy) instead of lazy sequence:

    (with-open [result (make-query conn [\"SELECT foo FROM bar WHERE id = ?\" 1] {:lazy? false})]
      (doseq [row (:data result)]
        (println row)))

  NOTE: It strongly recommended not use this function directly and use a `with-query`
  macro that manage resources for you and return directly a seq instead of a
  QueryResult instance.
  "
  [conn sql-with-params & {:keys [lazy?] :or {lazy? false} :as options}]
  {:pre [(or (instance? PreparedStatement sql-with-params)
             (vector? sql-with-params))
         (instance? Connection conn)]}
  (let [connection (:connection conn)
        stmt       (cond
                     (instance? PreparedStatement sql-with-params)
                     sql-with-params

                     (vector? sql-with-params)
                     (make-prepared-statement conn sql-with-params)

                     (string? sql-with-params)
                     (make-prepared-statement conn [sql-with-params]))]
    (let [rs (.executeQuery stmt)]
      (if lazy?
        (QueryResult. stmt rs (result-set-lazyseq rs))
        (QueryResult. stmt rs (result-set-vec rs))))))

(defmacro with-query
  "Idiomatic dsl macro for `query` function that automatically closes
  all resources when context is reached.

  Example:

    (with-query conn results
      ['SELECT name FROM people WHERE id = ?' [1]]
      (doseq [row results]
        (println row)))
  "
  [conn bindname sql-with-params & body]
  `(with-open [rs# (make-query ~conn ~sql-with-params)]
     (let [~bindname (:data rs#)]
       ~@body)))
