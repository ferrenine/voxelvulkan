#define SENTINAL_OUT_OF_BOUNDS uint(-1)

layout(push_constant) uniform PushConstants {
    uint queueIdx;
};

layout(binding = NODE_QUEUE_META_BINDING, std430) restrict buffer NodeQueueMeta {
    uvec4 nodeQueueMetadata[MAX_ITERATIONS];
};

layout(binding = NODE_QUEUE_SOURCE_BINDING, std430) restrict readonly buffer NodeQueueSource {
    uint[] nodeQueueSource;
};

layout(binding = NODE_QUEUE_SINK_BINDING, std430) restrict writeonly buffer NodeQueueSink {
    uint[] nodeQueueSink;
};

uint getCurrentNode() {
    // Never trust the published count: clamp it to the scratch buffer size so
    // a dense layer cannot index past nodeQueueSource.
    uint count = min(nodeQueueMetadata[queueIdx].w, MAX_QUEUE_SIZE);
    if (count <= gl_GlobalInvocationID.x) {
        return SENTINAL_OUT_OF_BOUNDS;
    }
    return nodeQueueSource[gl_GlobalInvocationID.x];
}

// Dispatch-size cap for the next layer, derived from the same hard limit.
#define MAX_QUEUE_GROUPS ((MAX_QUEUE_SIZE + LOCAL_SIZE - 1) >> LOCAL_SIZE_BITS)

uint nodePushIndex = -1;
void pushNodesInit(uint nodeCount) {
    // queueIdx+1 must stay inside nodeQueueMetadata[].
    if (queueIdx >= (MAX_ITERATIONS-1)) {
        #ifdef DEBUG
        printf("LOG: Traversal tried inserting a node into next iteration, which is outside max iteration bounds. GID: %d, count: %d", gl_GlobalInvocationID.x, nodeCount);
        #endif
        nodePushIndex = -1;
        return;
    }

#ifdef USE_SUBGROUPS
    uint localOffset = subgroupExclusiveAdd(nodeCount);
    uint totalCount = subgroupAdd(nodeCount);
    uint baseIndex = 0;
    if (subgroupElect() && totalCount > 0) {
        baseIndex = atomicAdd(nodeQueueMetadata[queueIdx+1].w, totalCount);
        if (baseIndex + totalCount > MAX_QUEUE_SIZE) {
            atomicMin(nodeQueueMetadata[queueIdx+1].w, MAX_QUEUE_SIZE);
        }
    }
    baseIndex = subgroupBroadcastFirst(baseIndex);
    uint index = baseIndex + localOffset;
    uint inc = ((index+LOCAL_SIZE)>>LOCAL_SIZE_BITS)-(index>>LOCAL_SIZE_BITS);
    if (inc != 0 && index < MAX_QUEUE_SIZE) {
        atomicAdd(nodeQueueMetadata[queueIdx+1].x, inc);
        atomicMin(nodeQueueMetadata[queueIdx+1].x, MAX_QUEUE_GROUPS);
    }
    nodePushIndex = index;
#else
    uint index = atomicAdd(nodeQueueMetadata[queueIdx+1].w, nodeCount);
    if (index + nodeCount > MAX_QUEUE_SIZE) {
        atomicMin(nodeQueueMetadata[queueIdx+1].w, MAX_QUEUE_SIZE);
    }
    //Increment first metadata value if it changes threash hold
    uint inc = ((index+LOCAL_SIZE)>>LOCAL_SIZE_BITS)-(index>>LOCAL_SIZE_BITS);
    if (inc != 0 && index < MAX_QUEUE_SIZE) {
        atomicAdd(nodeQueueMetadata[queueIdx+1].x, inc);
        atomicMin(nodeQueueMetadata[queueIdx+1].x, MAX_QUEUE_GROUPS);
    }
    nodePushIndex = index;
#endif
}

void pushNode(uint nodeId) {
    #ifdef DEBUG
    if (nodePushIndex == -1) {
        printf("LOG: Tried pushing node when push node wasnt successful. GID: %d, pushing: %d", gl_GlobalInvocationID.x, nodeId);
        return;
    }
    #endif
    if (nodePushIndex >= MAX_QUEUE_SIZE) {
        return;
    }
    nodeQueueSink[nodePushIndex++] = nodeId;
}

#define SIMPLE_QUEUE(type, name, bindingIndex) layout(binding = bindingIndex, std430) restrict buffer name##Struct { \
    type name##Index; \
    type##[] name; \
};