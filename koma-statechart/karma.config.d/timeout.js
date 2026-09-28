// The statechart property tests run hundreds of seeded charts per test. In a
// browser on CI that can take longer than Mocha's 2s default per test, so give
// each test more time. The tests are deterministic; this only avoids timeouts.
// The Store property tests also keep the page busy for seconds at a time, so
// Karma must not treat a slow, busy browser as disconnected.
config.set({
    client: {
        mocha: {
            timeout: 300000,
        },
    },
    browserNoActivityTimeout: 300000,
    browserDisconnectTimeout: 60000,
    pingTimeout: 60000,
});
