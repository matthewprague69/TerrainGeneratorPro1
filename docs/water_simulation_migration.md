# Water System Migration: Overlay Surface ➜ Logical Liquid

This plan replaces the current "animated overlay" water with a **logical liquid model** that behaves consistently with terrain, chunk boundaries, and performance constraints.

## Why the current approach feels wrong

Current behavior is mostly a rendered surface with wave offsets. That gives nice motion, but it does not model:

- Mass conservation (water amount moving between cells).
- Terrain-driven flow direction.
- Stable chunk-edge continuity during updates.
- Distinct states (still lake, flowing river, shoreline interaction) from one unified simulation.

## Target model

Use a **2D shallow-water style simulation** on a low-resolution simulation grid per chunk, then render from that grid.

Each sim cell stores:

- `h` = water column height (above terrain),
- `u, v` = horizontal velocity (x/z),
- optional `foam` accumulator for visual persistence.

Terrain elevation `z_bed` is static sampled terrain. Surface elevation is:

- `eta = z_bed + h`

This gives real liquid logic while keeping cost manageable.

## Simulation equations (practical form)

Per time step `dt` (fixed, e.g. 1/30 or 1/60):

1. Compute pressure-gradient acceleration from neighboring surface elevation:
   - `u += -g * d(eta)/dx * dt`
   - `v += -g * d(eta)/dz * dt`

2. Add bed friction/damping (stabilizes and prevents runaway):
   - `u *= (1 - k_friction * dt)`
   - `v *= (1 - k_friction * dt)`

3. Compute fluxes and update `h` by divergence (mass conservation):
   - `h -= dt * div(h*u, h*v)`

4. Clamp tiny/negative water heights:
   - `h = max(0, h)`

5. Apply rain/sources/sinks if needed (optional).

This is enough to produce direction, pooling, flow-through saddles/channels, and consistent behavior.

## Chunk-edge synchronization (critical)

To prevent cracks/holes:

- Keep a **1-cell ghost border** around each chunk simulation grid.
- At each sim step:
  1. Exchange edge `h, u, v` with neighbors into ghost cells.
  2. Step simulation.
  3. Optionally blend/copy boundary row back for strict continuity.

If neighbor not loaded yet:

- hold previous edge state, or
- use mirrored boundary + low-pass until neighbor arrives.

Never clear edge data abruptly on chunk rebuild.

## Rendering from simulation (not overlay)

Render mesh vertices directly from `eta` sampled from sim grid (plus tiny capillary detail noise only).

- Normals from central difference on `eta` (from sim surface, not synthetic wave only).
- Foam from physically meaningful proxies:
  - high `|velocity|`,
  - high `|divergence|` / curl,
  - breaking near steep depth gradients.
- Color from depth (`h`), flow speed (`|u,v|`), and bed color tint.

This makes visuals represent liquid state, not a decoupled animated layer.

## Performance strategy (so it stays fast)

- Sim grid much lower than render grid (e.g. 16×16 or 32×32 per chunk).
- Fixed tick rate simulation; interpolate for render.
- Sim only for chunks in/near view. Distant chunks:
  - frozen state or lower-frequency updates.
- Structure-of-arrays buffers (`float[] h, u, v`) for cache efficiency.
- Dirty-region stepping: only update active water chunks.

## Migration phases

### Phase 1 (safe foundation)

- Introduce `WaterSimChunk` data structure (`h/u/v`, ghost border).
- Initialize `h` from current water mask/depth.
- Keep existing renderer, but source vertex heights from `eta` instead of procedural wave offsets where available.

### Phase 2 (true liquid)

- Enable fixed-step shallow-water update loop.
- Add neighbor edge exchange.
- Remove most synthetic wave displacement; keep only tiny detail normal noise.

### Phase 3 (quality)

- Foam persistence field and advected foam.
- River source terms and lake level equilibrium.
- Optional obstacles/interactions.

### Phase 4 (optimization)

- LOD sim grids,
- multithreaded sim jobs,
- vectorized math.

## Suggested engine integration points

- `TerrainManager`: own water simulation tick scheduler and chunk edge exchange order.
- `Chunk`: store/render from simulation-backed water surface buffer.
- Current `drawWater(...)`: consume precomputed per-frame water surface data only; no heavy rebuilds in draw call.

## Practical first implementation checklist

1. Add `WaterSimChunk` class and arrays.
2. Add fixed-step `updateWaterSimulation(dt)` in manager.
3. Add neighbor edge copy method.
4. Replace `getWaterHeightAt(...)` source with simulated `eta` sampling.
5. Keep fallback to old wave path behind debug toggle until stable.

---

If you want, the next step can be a direct code patch for **Phase 1** in this repo:

- add `WaterSimChunk`,
- initialize from existing water patches,
- use sim surface heights for rendering,
- keep legacy wave path as fallback flag.
