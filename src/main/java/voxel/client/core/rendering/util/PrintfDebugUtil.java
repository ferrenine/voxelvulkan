package voxel.client.core.rendering.util;

import voxel.client.core.vk.shader.IShaderProcessor;
import voxel.client.core.vk.shader.PrintfInjector;
import voxel.client.core.vk.shader.ShaderType;
import voxel.common.Logger;

import java.util.ArrayList;
import java.util.List;

public final class PrintfDebugUtil {
    public static final boolean ENABLE_PRINTF_DEBUGGING = System.getProperty("voxel.enableShaderDebugPrintf", "false").equals("true");

    private static final List<String> printfQueue2 = new ArrayList<>();
    private static final List<String> printfQueue = new ArrayList<>();
    public static final IShaderProcessor PRINTF_processor;
    private static final PrintfInjector PRINTF_object;


    static {
        if (ENABLE_PRINTF_DEBUGGING) {
            //AUDIT-FIXME: printf buffer is injected at binding 20 but
            // PrintfInjector.bind() is a no-op and nothing calls ssbo(20,...)
            // anywhere — enabling the flag produces a shader that declares an
            // unbound descriptor.
            PRINTF_object = new PrintfInjector(50000, 20, line -> {
                if (line.startsWith("LOG")) {
                    Logger.info(line);
                }
                printfQueue.add(line);
            }, printfQueue::clear);
            PRINTF_processor = PRINTF_object;
        } else {
            PRINTF_object = null;
            //Todo add a dummy processor that just removes all the printf calls
            PRINTF_processor = new IShaderProcessor() {
                @Override
                public String process(ShaderType type, String src) {
                    //AUDIT-FIXME: blanket substring replace also mangles
                    // identifiers/comments containing "printf" — needs the
                    // balanced-brace matcher from the linked SO post.
                    return src.replace("printf", "//printf");
                }
            };
        }
    }

    public static void tick() {
        if (ENABLE_PRINTF_DEBUGGING) {
            printfQueue2.clear();
            printfQueue2.addAll(printfQueue);
            printfQueue.clear();
            PRINTF_object.download();
        }
    }

    public static void addToOut(List<String> out) {
        if (ENABLE_PRINTF_DEBUGGING) {
            out.add("Printf Queue: ");
            out.addAll(printfQueue2);
        }
    }

    public static void bind() {
        if (ENABLE_PRINTF_DEBUGGING) {
            PRINTF_object.bind();
        }
    }
}
