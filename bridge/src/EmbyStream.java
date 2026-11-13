package com.rplayer.embybridge;

import android.net.Uri;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EmbyStream {
    private static final Pattern STREAM_PATH = Pattern.compile(
            "(.*/videos/[0-9]+/stream)(?:\\.[a-z0-9]+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BYTE_RANGE = Pattern.compile("bytes 1-1/[0-9]+");

    private EmbyStream() {}

    static Uri directStreamFor(Uri stream) {
        if (!"http".equals(stream.getScheme()) && !"https".equals(stream.getScheme())) {
            return null;
        }
        String path = stream.getEncodedPath();
        String query = stream.getEncodedQuery();
        if (path == null || query == null) {
            return null;
        }
        Matcher endpoint = STREAM_PATH.matcher(path);
        if (!endpoint.matches()) {
            return null;
        }

        boolean mediaSource = false;
        boolean playSession = false;
        boolean device = false;
        boolean codec = false;
        boolean explicitlyTranscoded = false;
        List<String> parameters = new ArrayList<>();
        for (String parameter : query.split("&", -1)) {
            String[] parts = parameter.split("=", 2);
            String name = Uri.decode(parts[0]).toLowerCase(Locale.ROOT);
            String value = parts.length == 2 ? Uri.decode(parts[1]) : "";
            if (name.equals("livestreamid") && !value.isEmpty()) {
                return null;
            }
            if (name.equals("static")) {
                if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
                    return null;
                }
                explicitlyTranscoded = "false".equalsIgnoreCase(value) || "0".equals(value);
                continue;
            }
            mediaSource |= name.equals("mediasourceid") && !value.isEmpty();
            playSession |= name.equals("playsessionid") && !value.isEmpty();
            device |= name.equals("deviceid") && !value.isEmpty();
            codec |= (name.equals("videocodec") || name.equals("audiocodec")) && !value.isEmpty();
            parameters.add(parameter);
        }
        if (!mediaSource || !playSession || !device || (!codec && !explicitlyTranscoded)) {
            return null;
        }

        parameters.add("Static=true");
        return stream.buildUpon()
                .encodedPath(endpoint.group(1))
                .encodedQuery(String.join("&", parameters))
                .build();
    }

    static boolean supportsSeeking(Uri directStream) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(directStream.toString()).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestProperty("Range", "bytes=1-1");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) {
                return false;
            }
            String range = connection.getHeaderField("Content-Range");
            return range != null && BYTE_RANGE.matcher(range).matches();
        } catch (IOException | IllegalArgumentException unavailable) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
