// The statechart property tests run hundreds of seeded charts per test. In a
// browser on CI that can take longer than Mocha's 2s default per test, so give
// each test more time. The tests are deterministic; this only avoids timeouts.
config.set({
    client: {
        mocha: {
            timeout: 60000,
        },
    },
});
