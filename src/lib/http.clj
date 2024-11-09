(ns lib.http)

(defn internal-server-error [body]
  {:status  500
   :headers {}
   :body    body})
