(ns factorio-mod-tool.http.static
  "Serve static files for the browser GUI from resources/public/.")

(def ^:private fs (js/require "fs"))
(def ^:private path-mod (js/require "path"))

(def ^:private mime-types
  {"html" "text/html; charset=utf-8"
   "js"   "application/javascript; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "ico"  "image/x-icon"})

(defn- static-root []
  (.resolve path-mod (.dirname path-mod js/__filename) ".." "resources" "public"))

(defn serve-static
  "Serve a static file from resources/public/. Falls back to index.html for SPA routing."
  [^js req ^js res]
  (let [url (.-url req)
        url-path (first (.split url "?"))
        file-path (if (= url-path "/")
                    "index.html"
                    (subs url-path 1))
        full-path (.join path-mod (static-root) file-path)
        ext (last (.split file-path "."))]
    (.access fs full-path (.-constants.F_OK fs)
      (fn [err]
        (let [serve-path (if err
                           (.join path-mod (static-root) "index.html")
                           full-path)
              serve-ext (if err "html" ext)
              content-type (get mime-types serve-ext "application/octet-stream")]
          (.readFile fs serve-path
            (fn [read-err data]
              (if read-err
                (do
                  (.writeHead res 404 #js {"Content-Type" "text/plain"})
                  (.end res "Not found"))
                (do
                  (.writeHead res 200 #js {"Content-Type" content-type
                                           "Access-Control-Allow-Origin" "*"})
                  (.end res data))))))))))
