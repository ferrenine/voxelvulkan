/**
 * Hierarchical LOD node DAG and GPU-driven traversal/culling.
 *
 * <p>{@link voxel.client.core.rendering.hierarchical.NodeManager} maintains the
 * LOD node tree (top-level nodes -> children down to sections) and streams
 * built geometry to the GPU; {@code AsyncNodeManager} is its worker-thread
 * facade. {@code HierarchicalOcclusionTraverser} runs the compute traversal
 * (frustum + Hi-Z occlusion) producing visible node/section lists, and
 * {@code NodeCleaner} garbage-colletes unused nodes via compute.</p>
 */
package voxel.client.core.rendering.hierarchical;
