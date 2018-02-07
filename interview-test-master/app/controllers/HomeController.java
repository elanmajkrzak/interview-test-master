package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.event.LoggingAdapter;
import akka.japi.Pair;
import akka.japi.pf.PFBuilder;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import play.Application;
import play.Environment;
import play.api.Configuration;
import play.libs.F;
import play.libs.Json;
import play.mvc.*;

import akka.event.Logging;

import javax.inject.Inject;

import java.math.BigInteger;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;

import org.joda.time.DateTime;
import org.webjars.play.WebJarsUtil;

/**
 * A very simple chat client using websockets.
 */
public class HomeController extends Controller {

    private final Flow<String, String, NotUsed> userFlow;
    private final WebJarsUtil webJarsUtil;
    private static final int MAX_TEXT_LEN = 100;
    private boolean secureWebSockets;


    @Inject
    public HomeController(ActorSystem actorSystem,
                          Materializer mat,
                          WebJarsUtil webJarsUtil, Environment env) {


        secureWebSockets = env.isProd();


        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(this.getClass());
        LoggingAdapter logging = Logging.getLogger(actorSystem.eventStream(), logger.getName());

        //noinspection unchecked
        Source<String, Sink<String, NotUsed>> source = MergeHub.of(String.class)
                .log("source", logging)
                .recoverWithRetries(-1, new PFBuilder().match(Throwable.class, e -> Source.empty()).build());

        Sink<String, Source<String, NotUsed>> sink = BroadcastHub.of(String.class);

        Pair<Sink<String, NotUsed>, Source<String, NotUsed>> sinkSourcePair = source.toMat(sink, Keep.both()).run(mat);

        Sink<String, NotUsed> chatSink = sinkSourcePair.first();
        Source<String, NotUsed> chatSource = sinkSourcePair.second();

        this.userFlow = Flow.fromSinkAndSource(chatSink, chatSource).map(f -> {
            if (f.length() > MAX_TEXT_LEN) {
                return f.substring(0, MAX_TEXT_LEN);
            } else {
                return f;
            }
        }).log("userFlow", logging);


        this.webJarsUtil = webJarsUtil;
    }

    public Result motd() {

        ObjectNode o = Json.newObject();

        o.put("motd","Hello from Funraise, here is your message of the day");
        o.put("time", DateTime.now().toString());

        response().getHeaders().put("X-FUN-SIG",md5(Json.toJson(o).toString()));

        return ok(Json.toJson(o));
    }

    private static String md5(String input) {

        if(input == null) {
            throw new NullPointerException("input string should not be null for md5");
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] md5sum = md.digest(input.getBytes());
        return String.format("%032X", new BigInteger(1, md5sum));
    }

    public Result index() {
        Http.Request request = request();
        String url = routes.HomeController.chat().webSocketURL(request,secureWebSockets);
        return Results.ok(views.html.index.render(url, webJarsUtil));
    }

    public WebSocket chat() {
        return WebSocket.Text.acceptOrResult(request -> {
            if (sameOriginCheck(request)) {
                return CompletableFuture.completedFuture(F.Either.Right(userFlow));
            } else {
                return CompletableFuture.completedFuture(F.Either.Left(forbidden()));
            }
        });
    }

    /**
     * Checks that the WebSocket comes from the same origin.  This is necessary to protect
     * against Cross-Site WebSocket Hijacking as WebSocket does not implement Same Origin Policy.
     *
     * See https://tools.ietf.org/html/rfc6455#section-1.3 and
     * http://blog.dewhurstsecurity.com/2013/08/30/security-testing-html5-websockets.html
     */
    private boolean sameOriginCheck(Http.RequestHeader request) {
        return originMatches(request.getHeaders().get("Origin").get());
    }

    private boolean originMatches(String origin) {
        return true;
    }

}
