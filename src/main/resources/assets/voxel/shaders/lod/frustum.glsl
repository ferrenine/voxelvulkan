// Frustum plane test for an axis-aligned voxel node.
// Contract: planes are (nx,ny,nz,d) with d = -dot(n, pointOnPlane); a node at
// `pos` with edge length `size` is culled only when its positive vertex (the
// corner farthest along each plane normal) is behind a plane.

struct Frustum {
    vec4 planes[6];
};

// Distance test of the plane's positive vertex only.
bool insidePlane(vec4 plane, vec3 pos, float size) {
    vec3 corner = pos + vec3(size) * step(vec3(0.0), plane.xyz);
    return dot(plane.xyz, corner) + plane.w >= 0.0;
}

bool outsideFrustum(in Frustum frustum, vec3 pos, float size) {
    // Skip the far plane — culling at LOD distances never needs it.
    for (int i = 0; i < 5; i++) {
        if (!insidePlane(frustum.planes[i], pos, size)) {
            return true;
        }
    }
    return false;
}
