package com.xebialabs.overthere.cifs.winrs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xebialabs.overthere.CmdLine;
import com.xebialabs.overthere.ConnectionOptions;
import com.xebialabs.overthere.OverthereProcess;
import com.xebialabs.overthere.cifs.CifsConnection;
import com.xebialabs.overthere.local.LocalProcess;
import com.xebialabs.overthere.spi.AddressPortMapper;

import static com.google.common.base.Preconditions.checkArgument;
import static com.xebialabs.overthere.OperatingSystemFamily.WINDOWS;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.CIFS_PROTOCOL;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.DEFAULT_WINRM_ENABLE_HTTPS;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.DEFAULT_WINRS_ALLOW_DELEGATE;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.DEFAULT_WINRS_COMPRESSION;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.DEFAULT_WINRS_NOECHO;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.DEFAULT_WINRS_NOPROFILE;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.WINRM_ENABLE_HTTPS;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.WINRS_ALLOW_DELEGATE;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.WINRS_COMPRESSION;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.WINRS_NOECHO;
import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.WINRS_NOPROFILE;

public class CifsWinrsConnection extends CifsConnection {

	private ConnectionOptions options;

    public CifsWinrsConnection(String type, ConnectionOptions options, AddressPortMapper mapper) {
        super(type, options, mapper, true);
        checkArgument(os == WINDOWS, "Cannot start a " + CIFS_PROTOCOL + ":%s connection to a non-Windows operating system", cifsConnectionType.toString().toLowerCase());
        checkArgument(!username.contains("\\"), "Cannot start a " + CIFS_PROTOCOL + ":%s connection with an old-style Windows domain account [%s], use USER@DOMAIN instead.", cifsConnectionType.toString().toLowerCase(), username);

        this.options = options;
    }

    @Override
    public OverthereProcess startProcess(final CmdLine commandLine) {
        logger.info("Starting command [{}] on [{}]", commandLine.toCommandLine(os, true), this);

        final CmdLine winrs = new CmdLine();
        winrs.addArgument("winrs");
        winrs.addArgument("-remote:" + address + ":" + port);
        winrs.addArgument("-username:" + username);
        winrs.addPassword("-password:" + password);
        if (workingDirectory != null) {
            winrs.addArgument("-directory:" + workingDirectory.getPath());
        }
        if (options.getBoolean(WINRS_NOECHO, DEFAULT_WINRS_NOECHO)) {
            winrs.addArgument("-noecho");
        }
        if (options.getBoolean(WINRS_NOPROFILE, DEFAULT_WINRS_NOPROFILE)) {
            winrs.addArgument("-noprofile");
        }
        if (options.getBoolean(WINRS_ALLOW_DELEGATE, DEFAULT_WINRS_ALLOW_DELEGATE)) {
            winrs.addArgument("-allowdelegate");
        }
        if (options.getBoolean(WINRS_COMPRESSION, DEFAULT_WINRS_COMPRESSION)) {
            winrs.addArgument("-compression");
        }
        if (options.getBoolean(WINRM_ENABLE_HTTPS, DEFAULT_WINRM_ENABLE_HTTPS)) {
            winrs.addArgument("-usessl");
        }
        winrs.add(commandLine.getArguments());
        return LocalProcess.fromCommandLine(winrs, WINDOWS);
    }

    private static final Logger logger = LoggerFactory.getLogger(CifsWinrsConnection.class);

}
