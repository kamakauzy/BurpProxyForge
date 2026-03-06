package proxyforge.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class ProxyForgeJson
{
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ProxyForgeJson()
    {
    }

    public static ObjectMapper mapper()
    {
        return MAPPER;
    }

    public static <T> T read(String json, Class<T> type)
    {
        try
        {
            return MAPPER.readValue(json, type);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("Unable to parse JSON state", e);
        }
    }

    public static String write(Object value)
    {
        try
        {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("Unable to write JSON state", e);
        }
    }
}
