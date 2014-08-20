package org.apache.lish;

import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;

/**
 * 自己写的生命周期监视器。
 * Created by lishuang on 2014/8/20.
 */
public class LishLifecycleListener implements LifecycleListener {
	public void lifecycleEvent(LifecycleEvent event) {
		System.out.println("发生了声明周期事件:"+event.getType());
	}
}
