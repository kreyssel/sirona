package org.apache.commons.monitoring.metrics;

import org.apache.commons.monitoring.Counter;
import org.apache.commons.monitoring.Role;

public abstract class ThreadSafeCounter
    extends ObservableMetric
    implements Counter, Counter.Observable
{
    public ThreadSafeCounter( Role role )
    {
        super( role );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.apache.commons.monitoring.Metric#getType()
     */
    public Type getType()
    {
        return Type.COUNTER;
    }

    public void add( double delta )
    {
        threadSafeAdd( delta );
        fireValueChanged( delta );
    }

    /**
     * Implementation of this method is responsible to ensure thread safety. It is
     * expected to delegate computing to {@ #doThreadSafeAdd(long)}
     * @param delta
     */
    protected abstract void threadSafeAdd( double delta );

    protected void doThreadSafeAdd( double delta )
    {
        getSummary().addValue( delta );
    }

    protected void doReset()
    {
        getSummary().clear();
    }

}