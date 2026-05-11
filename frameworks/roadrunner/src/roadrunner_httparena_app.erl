-module(roadrunner_httparena_app).
-behaviour(application).

-export([start/2, stop/1]).

start(_StartType, _StartArgs) ->
    {ok, SupPid} = roadrunner_httparena_sup:start_link(),
    ok = roadrunner_httparena_dataset:load(),
    HttpPort = application:get_env(roadrunner_httparena, http_port, 8080),
    Routes = roadrunner_httparena_handler:routes(),
    {ok, _} = roadrunner:start_listener(httparena_http, #{
        port => HttpPort,
        routes => Routes,
        middlewares => [roadrunner_compress],
        %% 25 MB headroom for the upload profile (validator goes up to 20 MB).
        max_content_length => 26214400
    }),
    {ok, SupPid}.

stop(_State) ->
    ok.
