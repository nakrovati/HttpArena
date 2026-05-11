-module(roadrunner_httparena_app).
-behaviour(application).

-export([start/2, stop/1]).

start(_StartType, _StartArgs) ->
    {ok, SupPid} = roadrunner_httparena_sup:start_link(),
    ok = roadrunner_httparena_dataset:load(),
    _ = roadrunner_httparena_db:start_pool(),
    ok = roadrunner_httparena_crud:init(),
    Routes = roadrunner_httparena_handler:routes(),
    HttpPort = application:get_env(roadrunner_httparena, http_port, 8080),
    {ok, _} = roadrunner:start_listener(httparena_http, #{
        port => HttpPort,
        routes => Routes,
        middlewares => [roadrunner_compress],
        %% 25 MB headroom for the upload profile (validator goes up to 20 MB).
        max_content_length => 26214400
    }),
    H2cPort = application:get_env(roadrunner_httparena, h2c_port, 8082),
    {ok, _} = roadrunner:start_listener(httparena_h2c, #{
        port => H2cPort,
        routes => Routes,
        middlewares => [roadrunner_compress],
        max_content_length => 26214400,
        h2c => enabled
    }),
    case tls_opts() of
        {ok, TlsOpts} ->
            TlsPort = application:get_env(roadrunner_httparena, tls_port, 8081),
            {ok, _} = roadrunner:start_listener(httparena_tls, #{
                port => TlsPort,
                routes => Routes,
                middlewares => [roadrunner_compress],
                max_content_length => 26214400,
                tls => TlsOpts
            }),
            H2Port = application:get_env(roadrunner_httparena, h2_port, 8443),
            H2TlsOpts = [{alpn_preferred_protocols, [~"h2", ~"http/1.1"]} | TlsOpts],
            {ok, _} = roadrunner:start_listener(httparena_h2, #{
                port => H2Port,
                routes => Routes,
                middlewares => [roadrunner_compress],
                max_content_length => 26214400,
                tls => H2TlsOpts
            });
        skip ->
            ok
    end,
    {ok, SupPid}.

stop(_State) ->
    ok.

tls_opts() ->
    Cert = env_path("TLS_CERT_PATH", tls_cert, "/certs/server.crt"),
    Key = env_path("TLS_KEY_PATH", tls_key, "/certs/server.key"),
    case filelib:is_regular(Cert) andalso filelib:is_regular(Key) of
        true -> {ok, [{certfile, Cert}, {keyfile, Key}]};
        false -> skip
    end.

env_path(EnvVar, AppKey, Default) ->
    case os:getenv(EnvVar) of
        false -> application:get_env(roadrunner_httparena, AppKey, Default);
        V -> V
    end.
